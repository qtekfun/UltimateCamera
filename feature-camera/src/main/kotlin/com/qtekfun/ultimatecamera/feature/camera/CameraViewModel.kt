package com.qtekfun.ultimatecamera.feature.camera

import android.app.Application
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.audio.dsp.MeterSnapshot
import com.qtekfun.ultimatecamera.core.camera.HdrChoice
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.codec.VideoContainer
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.recording.StorageVideoOutput
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitNotice
import com.qtekfun.ultimatecamera.core.recording.WavReport
import com.qtekfun.ultimatecamera.core.settings.CaptureModeRepository
import com.qtekfun.ultimatecamera.core.settings.HdrRepository
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import com.qtekfun.ultimatecamera.core.settings.ShutterMode
import com.qtekfun.ultimatecamera.core.settings.VideoContainerChoice
import com.qtekfun.ultimatecamera.core.settings.photoHdr
import com.qtekfun.ultimatecamera.core.settings.portrait
import com.qtekfun.ultimatecamera.core.settings.styles
import com.qtekfun.ultimatecamera.core.settings.videoContainer
import com.qtekfun.ultimatecamera.core.settings.videoPortrait
import com.qtekfun.ultimatecamera.core.storage.AndroidStorageBackend
import com.qtekfun.ultimatecamera.core.storage.FreeSpaceStatus
import com.qtekfun.ultimatecamera.core.storage.LocationCheck
import com.qtekfun.ultimatecamera.core.storage.LocationLabels
import com.qtekfun.ultimatecamera.core.storage.LocationStatus
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.RecordingLocationRepository
import com.qtekfun.ultimatecamera.core.storage.RecordingTimeEstimate
import com.qtekfun.ultimatecamera.core.storage.SettingsRecordingLocationStore
import com.qtekfun.ultimatecamera.feature.camera.background.BackgroundStatus
import com.qtekfun.ultimatecamera.feature.camera.background.BackgroundWire
import com.qtekfun.ultimatecamera.feature.camera.remote.CameraRoleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the next take goes, as the status line shows it. */
data class StorageInfo(
    val chosenIsFolder: Boolean,
    val fellBack: Boolean,
    val unavailable: Boolean,
    val minutesLeft: Long?,
    val space: FreeSpaceStatus,
    /** Where takes go when it is not the phone: the drive's name or "Folder: …" (D50); null for the phone. */
    val place: String? = null
)

/** Peak levels of the take's audio, 0..1 linear, for the bar meter; clip when a channel hit full scale recently. */
data class AudioLevels(val left: Float, val right: Float, val clip: Boolean)

/**
 * What changes many times a second during a take (timer and audio meter, 15 Hz), kept out of [CameraUiState] so only the
 * timecode and the meter recompose at that rate, not the whole camera screen (D68).
 */
data class TakeLive(val elapsedMs: Long = 0, val audio: AudioLevels? = null)

data class CameraUiState(
    val camera: CameraState = CameraState.Closed,
    val profile: RecordingProfile? = null,
    val codec: VideoCodec = VideoCodec.HEVC,
    val profiles: List<RecordingProfile> = emptyList(),
    val refused: Set<RecordingProfile> = emptySet(),
    val storage: StorageInfo? = null,
    /** A one-shot note for the user (saved file name, problem); cleared by [CameraViewModel.consumeMessage]. */
    val message: CameraMessage? = null
)

sealed interface CameraMessage {
    data class Saved(val fileName: String) : CameraMessage

    data class Problem(val detail: String) : CameraMessage

    data class FellDown(val from: String, val to: String) : CameraMessage

    data object NoStorage : CameraMessage

    /** The guard ended the take (heat, battery, storage) and saved [fileName] (D47). */
    data class Stopped(val reason: StopReason, val fileName: String) : CameraMessage

    data object ThermalWarning : CameraMessage

    /** The app was closed by the system while [fileName] was being written (D66): that MP4 is likely unplayable. */
    data class Interrupted(val fileName: String) : CameraMessage

    /** The heat forecast says SEVERE is coming: offer the next lower mode for the next take (D68). */
    data class StepDownOffer(val from: String, val to: String) : CameraMessage

    /** The camera stepped down to a lower mode after a hot take (accepted, or "Protect the phone", D68). */
    data class SteppedDown(val from: String, val to: String) : CameraMessage
    data class CodecUnsupported(val codec: VideoCodec) : CameraMessage

    /** [fileName] was saved, but the drive was too slow for its WAV: [seconds] of it are silence (D86). */
    data class WavGaps(val fileName: String, val seconds: Double) : CameraMessage

    /** The background blur in video was lowered or turned off to keep the frame rate (D89). */
    data class VideoBlur(val notice: VideoPortraitNotice) : CameraMessage

    companion object {
        /** What the user hears when a take ends: its problem first, then WAV gaps, else the saved name. */
        fun takeEnded(fileName: String, problem: String?, wav: WavReport?): CameraMessage = when {
            problem != null -> Problem(problem)
            wav != null && wav.hasGaps -> WavGaps(fileName, wav.droppedSeconds)
            else -> Saved(fileName)
        }
    }
}

/**
 * The main screen's camera (Phase 2): opens the best mode of [ProfileLadder] the phone configures, keeps the preview
 * running, records takes through core-storage (REQ-STO-001) with AAC audio, and ticks the timer and meter.
 *
 * The camera is opened in [onStart] and closed in [onStop]. When the app leaves the screen ([onHidden]) a take or the
 * camera role keeps running through [BackgroundWire]'s foreground service (D66, setting on by default); otherwise the
 * take is finished and saved (D40).
 */
class CameraViewModel(application: Application, settings: SettingsRepository) : AndroidViewModel(application) {
    private val controller = CameraController(application)

    /** Settings › Experimental (D37): gates the direct 8K modes (D71). */
    private val experimentalPrefs = settings as? HdrRepository

    /** Recording and monitor looks (LUTs, UC-Log), persisted in settings. */
    val looks = CameraLooks(application, settings, controller, viewModelScope)
    private val backend = AndroidStorageBackend(application)
    private val locations = RecordingLocationRepository(SettingsRecordingLocationStore(settings), backend)
    private val takeAudio = TakeAudio(application, settings)

    /** Settings › Recording › Container (D69), read at each REC. */
    private val containerSetting = settings.videoContainer

    /** Photo mode (D41): owns the camera instead of [controller] while active. */
    val photo = PhotoMode(
        application,
        backend,
        locations,
        settings.photoHdr,
        settings.portrait,
        (settings as? HdrRepository)?.hdrPrefs?.map { it.experimental },
        settings.styles
    )

    /** The last-photo viewer's restyle (D79 follow-up): a new styled JPEG from the unstyled copy, no re-capture. */
    val restyle = PhotoRestyle(viewModelScope, AndroidRestyleStore(application, backend))
    private var ladder = ProfileLadder.profiles(experimental = false)
    private val lock = Mutex()
    private var ticker: Job? = null
    private var cameraId: String? = null
    private var location: LocationCheck? = null

    /** The on-screen location picker (D50); a change re-checks the storage line, never during a take. */
    val storageChoice = StorageChoice(viewModelScope, locations, backend) {
        lock.withLock { if (_state.value.camera !is CameraState.Recording) refreshStorage() }
    }

    private val _state = MutableStateFlow(CameraUiState(profiles = ladder, profile = ladder.first()))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val _live = MutableStateFlow(TakeLive())

    /** Timer and audio meter of the take, 15 Hz while recording. */
    val live: StateFlow<TakeLive> = _live.asStateFlow()

    /** Battery, heat and take health for the status bar; also stops or splits takes (D47). */
    private val recordingMonitor = RecordingMonitor(application, controller, backend) { action -> onGuard(action) }
    val status: StateFlow<StatusBarState> = recordingMonitor.state

    /** Battery, temperature, storage and predictive alerts (D52); [HealthState.toDeviceHealth] for the remote monitor. */
    val health: StateFlow<HealthState> = recordingMonitor.health
    private val alertVibration = viewModelScope.launch { settings.settings.collect { recordingMonitor.vibrateOnCritical = it.alertVibration } }

    /** Volume keys start and stop recording (setting, on by default). */
    val volumeKeys: StateFlow<Boolean> = settings.settings.map { it.volumeKeysRecord }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Time-of-day timecode of the takes and, when chosen, of the screen (D58). */
    val timecode = TimecodeDisplay(settings as? CaptureModeRepository, viewModelScope, controller) { _state.value.profile?.fps ?: 0 }

    /** ISO, shutter, WB, focus, EV and locks (REQ-CAM-002), see ControlsPanel.kt. */
    val manualControls = ManualControls(controller)

    /** Phone tier, preview load under heat and the guided step down (REQ-NFR-001, D68). */
    private val performance = PerformanceWire(
        application,
        settings,
        controller,
        viewModelScope,
        profile = { _state.value.profile },
        ladder = { _state.value.profiles },
        offer = { message -> _state.update { it.copy(message = message) } },
        apply = ::selectProfile
    )

    init {
        viewModelScope.launch { health.collect(performance::onHealth) }
    }

    /** Preview load (D68): the preview view caps its surface size with it. */
    val previewLoad: StateFlow<PreviewLoad> get() = performance.load

    /** The user took the step-down offer: the next take starts one mode lower (D68). */
    fun acceptStepDown() = performance.acceptStepDown()

    /** Monitoring tools of the preview (REQ-MON-001), applied under the preview load: see [MonitorBinding]. */
    private val monitorBinding = MonitorBinding(settings, viewModelScope, performance::monitor)
    val monitor: StateFlow<MonitorUiState> get() = monitorBinding.state

    fun setMonitorPrefs(prefs: com.qtekfun.ultimatecamera.core.settings.MonitorPrefs) = monitorBinding.set(prefs)

    /** Multicamera camera role (D56): remote control, status and monitor video, slave mode. */
    val cameraRole = CameraRoleBinding(application, viewModelScope, controller, this)

    /** Proxy recording and the experimental second camera (D59). */
    val multicam = MulticamWire(application, settings, controller, backend, viewModelScope)

    /** Lens selector, pinch zoom and stabilisation (REQ-CAM-001, REQ-CAM-003, D62). */
    val lenses: LensWire = LensWire(application, controller, viewModelScope) { id, zoom -> switchCamera(id, zoom) }

    /** Background blur in video (D89): the Blur chip; on/off and a settings change reopen the video camera. */
    val videoPortrait = VideoPortraitControl(
        controller.videoPortrait,
        settings.videoPortrait?.videoPortraitEnabled,
        (settings as? HdrRepository)?.hdrPrefs?.map { it.experimental },
        viewModelScope,
        reopen = { if (!photo.active && controller.state.value is CameraState.Previewing) _state.value.profile?.let(::selectProfile) },
        onNotice = { notice -> _state.update { it.copy(message = CameraMessage.VideoBlur(notice)) } }
    )

    /** HDR viewfinder and the experimental sensor HDR toggle (D63). */
    val hdr = HdrWire(application, settings, controller, viewModelScope) { _state.value.profile?.let(::selectProfile) }

    /** Background takes and camera role (D66): foreground service, notification, interrupted-take marker. */
    private val background: BackgroundWire = BackgroundWire(
        application,
        settings,
        viewModelScope,
        controller.state,
        cameraRole.ui.map { it.active },
        combine(_state, recordingMonitor.health, cameraRole.ui) { ui, health, role ->
            val recording = ui.camera as? CameraState.Recording
            BackgroundStatus(
                recording = recording != null,
                startedAtElapsedMs = recording?.startedAtElapsedMs ?: 0,
                fileName = recording?.fileName,
                mode = ui.profile?.label,
                fps = ui.profile?.fps ?: 0,
                storageMinutes = health.storageMinutesLeft ?: ui.storage?.minutesLeft,
                roleActive = role.active
            )
        },
        onIdleHidden = { onStop() },
        // Notification Stop: the take, else the camera role.
        onStopRequested = { if (_state.value.camera is CameraState.Recording) stopRecording() else cameraRole.stop() }
    )

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
        background.interruptedTake?.let { name -> _state.update { it.copy(message = CameraMessage.Interrupted(name)) } }
    }

    fun onStart() = viewModelScope.launch {
        val kept = background.shown()
        recordingMonitor.start(viewModelScope)
        cameraRole.start()
        lock.withLock {
            // Kept running off screen (D66): the preview reattaches by itself, nothing to reopen.
            if (kept && controller.state.value.let { it is CameraState.Previewing || it is CameraState.Recording }) return@withLock
            val id = cameraId ?: withContext(Dispatchers.Default) { controller.mainBackCamera() }.also { cameraId = it }
            if (id == null) {
                _state.update { it.copy(message = CameraMessage.Problem("no back camera")) }
                return@withLock
            }
            lenses.load()
            useLadderOf(id)
            applyStartModeOnce()
            if (photo.active) photo.open(id) else openBest(id, _state.value.profile ?: ladder.first())
            lenses.cameraOpened(id)
            refreshStorage()
        }
    }

    /** First start only: video, photo or the last mode used, as chosen in Settings. */
    private suspend fun applyStartModeOnce() {
        if (startModeApplied) return
        startModeApplied = true
        val prefs = modes?.captureModePrefs?.first() ?: return
        photo.select(if (prefs.modeAtStart == ShutterMode.PHOTO) CaptureMode.PHOTO else CaptureMode.VIDEO)
    }

    private val modes = settings as? CaptureModeRepository
    private var startModeApplied = false

    /** ON_STOP (Home, lock, another app): keep the take or role running off screen when allowed (D66), else [onStop]. */
    fun onHidden() {
        if (!background.hidden() || photo.active) onStop()
    }

    fun onStop() = viewModelScope.launch {
        background.left()
        recordingMonitor.stop()
        cameraRole.stop()
        lock.withLock {
            finishTake()
            multicam.cameraClosed()
            controller.close()
            photo.close()
        }
    }

    /** Switches between video and photo (D41), reopening the camera in the new mode; ignored while recording. */
    fun setCaptureMode(mode: CaptureMode) = viewModelScope.launch {
        lock.withLock {
            val id = cameraId ?: return@withLock
            if (_state.value.camera is CameraState.Recording || !photo.select(mode)) return@withLock
            modes?.setLastMode(if (mode == CaptureMode.PHOTO) ShutterMode.PHOTO else ShutterMode.VIDEO)
            if (mode == CaptureMode.PHOTO) {
                multicam.cameraClosed()
                controller.close()
                photo.open(id)
            } else {
                photo.close()
                openBest(id, _state.value.profile ?: ladder.first())
            }
        }
    }

    /** The photo HDR choice (D64): reopens the photo camera with it. */
    fun setPhotoHdr(choice: HdrChoice) = viewModelScope.launch {
        lock.withLock { photo.setHdr(choice, cameraId ?: return@withLock) }
    }

    /** The Portrait chip (D73): reopens the photo camera when it turns on or off. */
    fun setPortrait(portrait: PortraitUi) = viewModelScope.launch {
        lock.withLock { photo.setPortrait(portrait, cameraId ?: return@withLock) }
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

    /** The probed ladder of camera [id] (results are per camera id), or the fixed one. */
    private suspend fun useLadderOf(id: String) {
        val codec = _state.value.codec
        val fixed = ProfileLadder.profiles(experimental = false, codec = codec)
        // Direct 8K modes (D71) only with Settings › Experimental on, and only those the auto-configuration held.
        val experimental = experimentalPrefs?.hdrPrefs?.first()?.experimental == true
        useLadder(withContext(Dispatchers.IO) { ProbedLadder.load(getApplication(), id, codec, fixed, experimental) })
    }

    /**
     * Opens camera [id] (null = the open one again, e.g. for a new stabilisation) at [zoomRatio] (D62): its own probed
     * ladder, the mode closest to the current one. Never during a take nor in photo mode.
     */
    private suspend fun switchCamera(id: String?, zoomRatio: Float) = lock.withLock {
        if (_state.value.camera is CameraState.Recording || photo.active) return@withLock
        val target = id ?: cameraId ?: return@withLock
        val from = _state.value.profile
        if (target != cameraId) {
            // The second camera may be the one asked for (front): it closes first and is paired again after the opening.
            multicam.cameraClosed()
            cameraId = target
            _state.update { it.copy(refused = emptySet()) }
            useLadderOf(target)
        }
        val start = ladder.firstOrNull { from != null && it.width == from.width && it.fps == from.fps && it.tenBit == from.tenBit } ?: ladder.first()
        openBest(target, start)
        lenses.cameraOpened(target)
        controller.setControls(controller.controls.value.copy(zoomRatio = zoomRatio))
        refreshStorage()
    }

    /** Switches to [modes] (auto-configuration results, D42) when they differ, starting from the best of them. */
    private fun useLadder(modes: List<RecordingProfile>) {
        if (modes == ladder) return
        ladder = modes
        _state.update { it.copy(profiles = modes, profile = modes.first(), refused = emptySet()) }
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
                multicam.cameraOpened(id, candidate.label)
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
        controller.setPreviewRotation(degrees)
        multicam.setDisplayRotation(degrees)
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

    /**
     * REC from a monitor or master (D56): at once, or armed for camera-clock instant [atCameraNanos] (synchronised start,
     * D58). True when the take is running (or armed) afterwards.
     */
    internal suspend fun startFromRemote(atCameraNanos: Long? = null): Boolean = lock.withLock {
        if (controller.state.value is CameraState.Previewing && !photo.active) startTake(controller.uprightRotation(displayRotation), atCameraNanos)
        controller.state.value is CameraState.Recording
    }

    internal fun stopFromRemote() = stopRecording()

    private suspend fun startTake(orientationHint: Int, atCameraNanos: Long? = null) {
        val id = cameraId ?: return
        val check = locations.check().also { location = it }
        refreshStorage(check)
        val target = check.effective
        if (target == null || _state.value.storage?.space == FreeSpaceStatus.FULL) {
            _state.update { it.copy(message = CameraMessage.NoStorage) }
            return
        }
        val mov = containerSetting?.videoContainer?.first() == VideoContainerChoice.MOV
        val output = try {
            withContext(Dispatchers.IO) { StorageVideoOutput.create(backend, target, id, container = if (mov) VideoContainer.MOV else VideoContainer.MP4) }
        } catch (e: java.io.IOException) {
            _state.update { it.copy(message = CameraMessage.Problem(e.message ?: "storage")) }
            return
        }
        val audio = if (audioAllowed) takeAudio.config(backend, target, output) else null
        if (atCameraNanos != null) {
            controller.startRecordingAt(output, audio, orientationHint, atCameraNanos)
        } else {
            controller.startRecording(output, audio, orientationHint)
        }
        if (controller.state.value is CameraState.Recording) {
            recordingMonitor.takeStarted(target, takeAudio.streams((_state.value.profile ?: return).bitrateBps.toLong()))
            multicam.takeStarted(target)
            startTicker()
        }
    }

    /** Acts on [RecordingMonitor]'s guard: warn, continue in the next part, or stop the take and say why. */
    private fun onGuard(action: GuardAction) = viewModelScope.launch {
        lock.withLock {
            val recording = _state.value.camera as? CameraState.Recording ?: return@withLock
            when (action) {
                GuardAction.None -> Unit
                GuardAction.WarnThermal -> _state.update { it.copy(message = CameraMessage.ThermalWarning) }
                GuardAction.Split -> controller.splitTake()?.let { problem ->
                    finishTake()
                    _state.update { it.copy(message = CameraMessage.Problem(problem)) }
                }
                is GuardAction.Stop -> {
                    val fileName = (controller.state.value as? CameraState.Recording)?.fileName ?: recording.fileName
                    finishTake()
                    _state.update { it.copy(message = CameraMessage.Stopped(action.reason, fileName)) }
                    background.guardStopped(getApplication<Application>().getString(stopReasonText(action.reason), fileName))
                }
            }
        }
    }

    private suspend fun finishTake() {
        val recording = _state.value.camera as? CameraState.Recording ?: return
        ticker?.cancel()
        val extra = multicam.takeFinished()
        val problem = controller.stopRecording() ?: extra
        _live.value = TakeLive()
        _state.update { it.copy(message = CameraMessage.takeEnded(recording.fileName, problem, controller.lastWavReport)) }
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
                _live.value = TakeLive(SystemClock.elapsedRealtime() - recording.startedAtElapsedMs, levels)
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
            space = free?.let { RecordingTimeEstimate.classify(it, streams) } ?: FreeSpaceStatus.OK,
            place = check.effective?.takeIf { it != RecordingLocation.Default }?.let {
                LocationLabels.location(getApplication<Application>().resources, it, volume = (check.effectiveStatus as? LocationStatus.Ready)?.volume)
            }
        )
        _state.update { it.copy(storage = info) }
        recordingMonitor.setStorageTarget(check.effective, streams, free)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        // viewModelScope is already cancelled here: finish synchronously on a plain thread so a take is never lost.
        Thread {
            kotlinx.coroutines.runBlocking { controller.close() }
            background.shutdown()
            controller.shutdown()
            photo.shutdown()
            multicam.shutdown()
        }.start()
    }

    private companion object {
        /** 15 Hz: smooth enough for a meter and a timer, cheap on modest phones. */
        const val TICK_MS = 66L
    }
}
