package com.qtekfun.ultimatecamera.feature.camera

import android.app.Application
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.audio.dsp.MeterSnapshot
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.recording.StorageVideoOutput
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import com.qtekfun.ultimatecamera.core.storage.AndroidStorageBackend
import com.qtekfun.ultimatecamera.core.storage.FreeSpaceStatus
import com.qtekfun.ultimatecamera.core.storage.LocationCheck
import com.qtekfun.ultimatecamera.core.storage.LocationStatus
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.RecordingLocationRepository
import com.qtekfun.ultimatecamera.core.storage.RecordingTimeEstimate
import com.qtekfun.ultimatecamera.core.storage.SettingsRecordingLocationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the next take goes, as the status line shows it. */
data class StorageInfo(val chosenIsFolder: Boolean, val fellBack: Boolean, val unavailable: Boolean, val minutesLeft: Long?, val space: FreeSpaceStatus)

/** Peak levels of the take's audio, 0..1 linear, for the bar meter; clip when a channel hit full scale recently. */
data class AudioLevels(val left: Float, val right: Float, val clip: Boolean)

data class CameraUiState(
    val camera: CameraState = CameraState.Closed,
    val profile: RecordingProfile? = null,
    val codec: VideoCodec = VideoCodec.HEVC,
    val profiles: List<RecordingProfile> = emptyList(),
    val refused: Set<RecordingProfile> = emptySet(),
    val elapsedMs: Long = 0,
    val framesEncoded: Int = 0,
    val storage: StorageInfo? = null,
    val audio: AudioLevels? = null,
    /** A one-shot note for the user (saved file name, problem); cleared by [CameraViewModel.consumeMessage]. */
    val message: CameraMessage? = null
)

sealed interface CameraMessage {
    data class Saved(val fileName: String) : CameraMessage

    data class Problem(val detail: String) : CameraMessage

    data class FellDown(val from: String, val to: String) : CameraMessage

    data object NoStorage : CameraMessage

    data class CodecUnsupported(val codec: VideoCodec) : CameraMessage
}

/**
 * The main screen's camera (Phase 2): opens the best mode of [ProfileLadder] the phone configures, keeps the preview
 * running, records takes through core-storage (REQ-STO-001) with AAC audio, and ticks the timer and meter.
 *
 * The camera is opened in [onStart] and closed in [onStop]: a take in progress is finished and saved when the app
 * leaves the foreground (no background recording yet, decision D40).
 */
class CameraViewModel(application: Application, settings: SettingsRepository) : AndroidViewModel(application) {
    private val controller = CameraController(application)
    private val backend = AndroidStorageBackend(application)
    private val locations = RecordingLocationRepository(SettingsRecordingLocationStore(settings), backend)
    private val takeAudio = TakeAudio(application, settings)

    /** Photo mode (D41): owns the camera instead of [controller] while active. */
    val photo = PhotoMode(application, backend, locations)
    private var ladder = ProfileLadder.profiles(experimental = false)
    private val lock = Mutex()
    private var ticker: Job? = null
    private var cameraId: String? = null
    private var location: LocationCheck? = null

    private val _state = MutableStateFlow(CameraUiState(profiles = ladder, profile = ladder.first()))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    /** Monitoring tools of the preview (REQ-MON-001): see [MonitorBinding]. */
    private val monitorBinding = MonitorBinding(settings, viewModelScope, controller::setMonitorSettings)
    val monitor: StateFlow<MonitorUiState> get() = monitorBinding.state

    fun setMonitorPrefs(prefs: com.qtekfun.ultimatecamera.core.settings.MonitorPrefs) = monitorBinding.set(prefs)

    /** Set once the camera and microphone permissions are granted. */
    var audioAllowed = false

    init {
        viewModelScope.launch {
            controller.state.collect { camera ->
                _state.update { it.copy(camera = camera) }
                // A camera lost mid-take: finish the file with what was recorded.
                if (camera is CameraState.Failed && !camera.rejected) stopRecording()
            }
        }
    }

    fun onStart() = viewModelScope.launch {
        lock.withLock {
            val id = cameraId ?: withContext(Dispatchers.Default) { controller.mainBackCamera() }.also { cameraId = it }
            if (id == null) {
                _state.update { it.copy(message = CameraMessage.Problem("no back camera")) }
                return@withLock
            }
            if (photo.active) photo.open(id) else openBest(id, _state.value.profile ?: ladder.first())
            refreshStorage()
        }
    }

    fun onStop() = viewModelScope.launch {
        lock.withLock {
            finishTake()
            controller.close()
            photo.close()
        }
    }

    /** Switches between video and photo (D41), reopening the camera in the new mode; ignored while recording. */
    fun setCaptureMode(mode: CaptureMode) = viewModelScope.launch {
        lock.withLock {
            val id = cameraId ?: return@withLock
            if (_state.value.camera is CameraState.Recording || !photo.select(mode)) return@withLock
            if (mode == CaptureMode.PHOTO) {
                controller.close()
                photo.open(id)
            } else {
                photo.close()
                openBest(id, _state.value.profile ?: ladder.first())
            }
        }
    }

    /** Takes one photo in photo mode; the saved name (or the problem) goes to the snackbar. */
    fun takePhoto() = viewModelScope.launch {
        lock.withLock { photo.take(cameraId ?: return@withLock)?.let { note -> _state.update { it.copy(message = note) } } }
    }

    fun selectProfile(profile: RecordingProfile) = viewModelScope.launch {
        lock.withLock {
            if (_state.value.camera is CameraState.Recording) return@withLock
            val id = cameraId ?: return@withLock
            _state.update { it.copy(refused = it.refused - profile) }
            openBest(id, profile)
            refreshStorage()
        }
    }

    /**
     * Switches the video codec: the ladder is rebuilt for it and the closest mode to the current one is opened. When the
     * phone has no encoder for [codec] at all, the previous codec stays and the user is told.
     */
    fun selectCodec(codec: VideoCodec) = viewModelScope.launch {
        lock.withLock {
            val current = _state.value
            if (current.camera is CameraState.Recording || codec == current.codec) return@withLock
            val id = cameraId ?: return@withLock
            val previous = ladder
            ladder = ProfileLadder.profiles(experimental = false, codec = codec)
            val from = current.profile
            val start = ladder.firstOrNull { from != null && it.width == from.width && it.fps == from.fps && it.tenBit == from.tenBit }
                ?: ladder.firstOrNull { from != null && it.width == from.width && it.fps == from.fps }
                ?: ladder.first()
            _state.update { it.copy(profiles = ladder, refused = emptySet(), codec = codec) }
            if (!openBest(id, start)) {
                ladder = previous
                _state.update { it.copy(profiles = previous, refused = emptySet(), codec = current.codec, message = CameraMessage.CodecUnsupported(codec)) }
                openBest(id, from ?: previous.first())
            }
            refreshStorage()
        }
    }

    /** Opens [profile], moving down the ladder while the phone refuses modes; false when every mode was refused. */
    private suspend fun openBest(id: String, profile: RecordingProfile): Boolean {
        var candidate: RecordingProfile? = profile
        while (candidate != null) {
            controller.open(id, candidate)
            val result = controller.state.value
            if (result !is CameraState.Failed || !result.rejected) {
                setDisplayRotation(displayRotation)
                _state.update { it.copy(profile = candidate) }
                if (candidate != profile) _state.update { it.copy(message = CameraMessage.FellDown(profile.label, candidate.label)) }
                return true
            }
            val failed = candidate
            _state.update { it.copy(refused = it.refused + failed) }
            candidate = ProfileLadder.next(failed, _state.value.refused, ladder)
        }
        return false
    }

    fun setPreview(surface: Surface?) = controller.setPreview(surface).also { photo.setPreview(surface) }

    /** The display's rotation in degrees; the preview follows it at once, a take keeps the one it started with. */
    fun setDisplayRotation(degrees: Int) {
        displayRotation = degrees
        photo.setDisplayRotation(degrees)
        controller.setPreviewRotation(controller.uprightRotation(degrees))
    }

    private var displayRotation = 0

    fun toggleRecording() = viewModelScope.launch {
        lock.withLock {
            when (_state.value.camera) {
                is CameraState.Recording -> finishTake()
                is CameraState.Previewing -> startTake(controller.uprightRotation(displayRotation))
                else -> Unit
            }
        }
    }

    private suspend fun startTake(orientationHint: Int) {
        val id = cameraId ?: return
        val check = locations.check().also { location = it }
        refreshStorage(check)
        val target = check.effective
        if (target == null || _state.value.storage?.space == FreeSpaceStatus.FULL) {
            _state.update { it.copy(message = CameraMessage.NoStorage) }
            return
        }
        val output = try {
            withContext(Dispatchers.IO) { StorageVideoOutput.create(backend, target, id) }
        } catch (e: java.io.IOException) {
            _state.update { it.copy(message = CameraMessage.Problem(e.message ?: "storage")) }
            return
        }
        controller.startRecording(output, if (audioAllowed) takeAudio.config(backend, target, output) else null, orientationHint)
        if (controller.state.value is CameraState.Recording) startTicker()
    }

    private suspend fun finishTake() {
        val recording = _state.value.camera as? CameraState.Recording ?: return
        ticker?.cancel()
        val problem = controller.stopRecording()
        _state.update {
            it.copy(
                audio = null,
                message = if (problem == null) CameraMessage.Saved(recording.fileName) else CameraMessage.Problem(problem)
            )
        }
        refreshStorage()
    }

    private fun stopRecording() = viewModelScope.launch { lock.withLock { finishTake() } }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            val snapshot = MeterSnapshot()
            while (isActive) {
                val recording = _state.value.camera as? CameraState.Recording ?: break
                val levels = controller.audio?.takeIf { it.readMeter(snapshot) }?.let {
                    AudioLevels(snapshot.peakLinear[0], snapshot.peakLinear[1.coerceAtMost(snapshot.capacity - 1)], snapshot.clip.any { c -> c })
                }
                _state.update {
                    it.copy(
                        elapsedMs = SystemClock.elapsedRealtime() - recording.startedAtElapsedMs,
                        framesEncoded = controller.framesEncoded(),
                        audio = levels
                    )
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun refreshStorage(known: LocationCheck? = null) {
        val check = known ?: locations.check().also { location = it }
        val profile = _state.value.profile ?: return
        val free = (check.effectiveStatus as? LocationStatus.Ready)?.freeBytes
        val streams = takeAudio.streams(profile.bitrateBps.toLong())
        val info = StorageInfo(
            chosenIsFolder = check.chosen is RecordingLocation.Folder,
            fellBack = check.fellBack,
            unavailable = check.effective == null,
            minutesLeft = free?.let { RecordingTimeEstimate.remainingMinutes(it, streams) },
            space = free?.let { RecordingTimeEstimate.classify(it, streams) } ?: FreeSpaceStatus.OK
        )
        _state.update { it.copy(storage = info) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        // viewModelScope is already cancelled here: finish synchronously on a plain thread so a take is never lost.
        Thread {
            kotlinx.coroutines.runBlocking { controller.close() }
            controller.shutdown()
            photo.shutdown()
        }.start()
    }

    private companion object {
        /** 15 Hz: smooth enough for a meter and a timer, cheap on modest phones. */
        const val TICK_MS = 66L
    }
}
