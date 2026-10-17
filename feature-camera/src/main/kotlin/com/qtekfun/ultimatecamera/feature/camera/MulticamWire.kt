package com.qtekfun.ultimatecamera.feature.camera

import android.app.Application
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.ConcurrentPairs
import com.qtekfun.ultimatecamera.core.recording.PairOption
import com.qtekfun.ultimatecamera.core.recording.SecondCamera
import com.qtekfun.ultimatecamera.core.recording.StorageVideoOutput
import com.qtekfun.ultimatecamera.core.settings.MulticamPrefs
import com.qtekfun.ultimatecamera.core.settings.MulticamRepository
import com.qtekfun.ultimatecamera.core.settings.SecondCameraRepository
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraDimens
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraShapes
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the camera screen shows of the multicamera options (D59). */
data class MulticamUiState(
    /** The take in progress also writes `<base>_proxy.mp4`. */
    val proxyRecording: Boolean = false,
    /** The concurrent second camera streaming now (shown picture-in-picture), or null. */
    val secondCameraId: String? = null,
    /** The experimental second camera is switched on in Settings. */
    val concurrentEnabled: Boolean = false,
    /** The camera pairs the phone reports, usable ones first, blocked ones with the reason (D62). */
    val pairs: List<PairOption> = emptyList(),
    /** The chosen pair ([PairOption.key]), null = automatic (front first). */
    val chosenPair: String? = null,
    /** The main mode open now (for the "refused with …" reason). */
    val mainMode: String = ""
)

/**
 * The camera screen's multicamera options (Phase 6, D59), kept out of [CameraViewModel] so it only needs small hooks:
 * the proxy setting is handed to the [CameraController], and the experimental second camera (REQ-NET-001) is opened
 * next to the main one where the phone reports a concurrent combination, recorded with every take as its own file and
 * shown picture-in-picture. Phones without concurrent combinations never open it, whatever the setting says.
 */
class MulticamWire internal constructor(
    application: Application,
    settings: SettingsRepository,
    private val controller: CameraController,
    private val backend: StorageBackend,
    private val scope: CoroutineScope
) {
    private val second = SecondCamera(application)
    private val lock = Mutex()
    private var prefs = MulticamPrefs()
    private val pairChoice = settings as? SecondCameraRepository
    private var chosen: String? = null
    private var mainId: String? = null
    private var mainMode = ""

    /** Pairs the phone refused, as `main|partner|mode`: not tried again with the same main mode. */
    private val refused = mutableSetOf<String>()
    private var recording = false
    private var displayRotation = 0

    private val _state = MutableStateFlow(MulticamUiState())
    val state: StateFlow<MulticamUiState> = _state.asStateFlow()

    init {
        (settings as? MulticamRepository)?.let { repo ->
            scope.launch {
                repo.multicamPrefs.collect { p ->
                    prefs = p
                    controller.recordProxy = p.recordProxy
                    lock.withLock { reconcile() }
                }
            }
        }
        pairChoice?.let { repo ->
            scope.launch {
                repo.secondCameraPair.collect { pair ->
                    lock.withLock {
                        chosen = pair
                        reconcile()
                    }
                }
            }
        }
    }

    /** Persists the pair to record (its [PairOption.key]; null = automatic). */
    fun choosePair(key: String?) {
        scope.launch { pairChoice?.setSecondCameraPair(key) }
    }

    /** The main camera [id] is open in video mode with [mode] (its label, for the refusal reason). */
    suspend fun cameraOpened(id: String, mode: String = "") = lock.withLock {
        mainId = id
        mainMode = mode
        reconcile()
    }

    /** The main camera closed (screen stopped, photo mode): the second one closes too. */
    suspend fun cameraClosed() = lock.withLock {
        mainId = null
        reconcile()
    }

    /** Opens or closes the second camera to match the setting; never during a take. */
    private suspend fun reconcile() {
        if (recording) return
        val id = mainId
        val partner = if (prefs.concurrentCamera && id != null) withContext(Dispatchers.Default) { second.partnerFor(id, chosen) } else null
        val wanted = partner?.takeIf { refusalKey(id, it) !in refused }
        if (wanted != second.cameraId) {
            second.close()
            if (wanted != null && id != null) {
                val problem = second.open(wanted)
                if (problem != null) refused += refusalKey(id, wanted)
                second.setPreviewRotation(displayRotation)
            }
        }
        publish()
    }

    private fun refusalKey(main: String?, partner: String) = "$main|$partner|$mainMode"

    private suspend fun publish() {
        val id = mainId
        val sets = if (prefs.concurrentCamera) withContext(Dispatchers.Default) { second.concurrentSets() } else emptySet()
        val refusedPartners = refused.mapNotNull { key -> key.split("|").takeIf { it[0] == id && it[2] == mainMode }?.get(1) }.toSet()
        _state.update {
            it.copy(
                secondCameraId = second.cameraId,
                concurrentEnabled = prefs.concurrentCamera,
                pairs = ConcurrentPairs.options(sets, id, refusedPartners),
                chosenPair = chosen,
                mainMode = mainMode
            )
        }
    }

    /** A take started into [target]: the second camera records its own file. */
    suspend fun takeStarted(target: RecordingLocation) = lock.withLock {
        _state.update { it.copy(proxyRecording = controller.proxyActive) }
        val id = second.cameraId ?: return@withLock
        val output = try {
            withContext(Dispatchers.IO) { StorageVideoOutput.create(backend, target, id) }
        } catch (_: IOException) {
            return@withLock
        }
        recording = second.startRecording(output, displayRotation) == null
    }

    /** The take ended: the second camera's file is finished; returns its problem (null when clean or absent). */
    suspend fun takeFinished(): String? = lock.withLock {
        _state.update { it.copy(proxyRecording = false) }
        if (!recording) return@withLock null
        recording = false
        second.stopRecording()?.let { "CAM${second.cameraId}: $it" }
    }

    fun setDisplayRotation(degrees: Int) {
        displayRotation = degrees
        second.setPreviewRotation(degrees)
    }

    fun setPipSurface(surface: android.view.Surface?) = second.setPreview(surface)

    /** Frees the second camera; called when the view model goes away (its scope is already cancelled). */
    fun shutdown() {
        runBlocking { second.close() }
        second.shutdown()
    }
}

/** The second camera's picture-in-picture preview; nothing when no second camera is open. */
@Composable
fun SecondCameraPip(wire: MulticamWire, modifier: Modifier = Modifier) {
    val state by wire.state.collectAsStateWithLifecycle()
    if (state.secondCameraId == null) return
    AndroidView(
        modifier = modifier.size(PIP_WIDTH.dp, PIP_HEIGHT.dp).clip(CameraShapes.surface).border(CameraTheme.hairlineBorder, CameraShapes.surface),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                // Above the main preview's SurfaceView.
                setZOrderMediaOverlay(true)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = wire.setPipSurface(holder.surface)

                    override fun surfaceDestroyed(holder: SurfaceHolder) = wire.setPipSurface(null)
                })
            }
        }
    )
}

private const val PIP_WIDTH = 160
private const val PIP_HEIGHT = 120

/** Clear of the audio meter at the start edge. */
internal val PIP_START = 56.dp

/** Above the portrait control bar, or near the bottom edge in landscape. */
internal fun pipBottom(portrait: Boolean) = if (portrait) PORTRAIT_BAR + CameraDimens.edge else CameraDimens.edge
