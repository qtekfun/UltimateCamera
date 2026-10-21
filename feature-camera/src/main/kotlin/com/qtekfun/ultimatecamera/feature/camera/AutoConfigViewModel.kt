package com.qtekfun.ultimatecamera.feature.camera

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.capabilities.AndroidCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.CombinedVendorExtension
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.NoVendorExtension
import com.qtekfun.ultimatecamera.core.capabilities.PacingVariants
import com.qtekfun.ultimatecamera.core.capabilities.ProbeEngine
import com.qtekfun.ultimatecamera.core.capabilities.ProbeListener
import com.qtekfun.ultimatecamera.core.capabilities.ProbeOutcome
import com.qtekfun.ultimatecamera.core.capabilities.ProbePlanner
import com.qtekfun.ultimatecamera.core.capabilities.ProbeResult
import com.qtekfun.ultimatecamera.core.capabilities.ProbeRun
import com.qtekfun.ultimatecamera.core.capabilities.SensorHdrExtension
import com.qtekfun.ultimatecamera.core.capabilities.StopReason
import com.qtekfun.ultimatecamera.core.capabilities.VendorExtension
import com.qtekfun.ultimatecamera.core.capabilities.VendorKeyFamilies
import com.qtekfun.ultimatecamera.core.capabilities.VendorKeyFamily
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.recording.probe.AdvertisedDeviceReader
import com.qtekfun.ultimatecamera.core.recording.probe.Camera2ProbeRunner
import com.qtekfun.ultimatecamera.core.settings.HdrRepository
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One line of the progress list: the configuration and how it ended. [marginal], [gapCount] and [maxGapMs] say how a
 * run with drops lost its frames (one short gap or many, D83).
 */
data class ProbeRow(
    val label: String,
    val outcome: ProbeOutcome,
    val measuredFps: Double?,
    val droppedFrames: Int?,
    val marginal: Boolean = false,
    val gapCount: Int? = null,
    val maxGapMs: Double? = null,
    /** Buffers the camera reported lost in the window (D104); shown after the measured text when any. */
    val buffersLost: Int? = null,
    /** The camera measured (D107): the list groups the rows per camera. */
    val cameraId: String? = null
) {
    constructor(result: ProbeResult) : this(
        result.config.label,
        result.outcome,
        result.measuredFps,
        result.droppedFrames,
        result.marginal,
        result.gapCount,
        result.maxGapMs,
        result.buffersLost,
        result.cameraId
    )

    /** The largest gap is worth showing only for a run that lost frames in gaps. */
    val shownGapMs: Double? get() = maxGapMs?.takeIf { (gapCount ?: 0) > 0 && (droppedFrames ?: 0) > 0 }

    /** Lost buffers are shown only after a measured rate, and only when there were any. */
    val shownBuffersLost: Int? get() = buffersLost?.takeIf { it > 0 && measuredFps != null }
}

enum class AutoConfigPhase { IDLE, RUNNING, DONE, FAILED }

data class AutoConfigUiState(
    val phase: AutoConfigPhase = AutoConfigPhase.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    val rows: List<ProbeRow> = emptyList(),
    val elapsedMs: Long = 0,
    val stopReason: StopReason? = null,
    val best: RecordingProfile? = null,
    val error: String? = null,
    /** Manufacturer keys the measured camera lists, by namespace (D101): what a vendor extension could work with. */
    val vendorFamilies: List<VendorKeyFamily> = emptyList(),
    /** The cameras of the run (D107), known before the first result. */
    val cameras: AutoConfigCameras? = null,
    /** The best mode of the front camera (D107), when the run measured one. */
    val frontBest: RecordingProfile? = null
) {
    /** The rows of the main camera, then those of the front camera (the plan puts the main camera's variants last). */
    val rowsByCamera: List<Pair<String?, List<ProbeRow>>>
        get() = rows.groupBy { it.cameraId }.toList().sortedBy { (id, _) -> if (id == cameras?.front) 1 else 0 }
}

/** The cameras an auto-configuration run measures (D107): the main back camera and, when the phone has one, the selfie camera. */
data class AutoConfigCameras(val main: String, val front: String? = null)

/** A finished auto-configuration run and the cameras it measured. */
data class AutoConfigRun(val run: ProbeRun, val cameras: AutoConfigCameras)

/** One auto-configuration run (the real Camera2 probes in the app, a fake in the tests). */
fun interface AutoConfigSession {
    /**
     * Probes the main back camera and the front camera (D107), telling [cameras] which they are before the first probe;
     * [experimental] adds the vendor sensor HDR, frame pacing and direct 8K variants on the main camera (D37, D63, D71, D104).
     */
    suspend fun run(experimental: Boolean, cameras: (AutoConfigCameras) -> Unit, listener: ProbeListener): AutoConfigRun
}

/** The vendor variants of an experimental run: sensor HDR, then frame pacing (D104). */
internal val experimentalVendor: VendorExtension = CombinedVendorExtension(SensorHdrExtension, PacingVariants)

/** [ProbeEngine] with the real [Camera2ProbeRunner] on the main back and front cameras; results go to the capability store. */
class Camera2AutoConfigSession(private val app: Application) : AutoConfigSession {
    override suspend fun run(experimental: Boolean, cameras: (AutoConfigCameras) -> Unit, listener: ProbeListener): AutoConfigRun {
        val store = AndroidCapabilities.store(app)
        val runner = Camera2ProbeRunner(app)
        val device = withContext(Dispatchers.Default) {
            runner.cleanUp()
            AdvertisedDeviceReader.read(app)
        }
        val ids = ProbePlanner.autoConfigCameraIds(device)
        val measured = AutoConfigCameras(ids.firstOrNull() ?: error("no camera"), ids.getOrNull(1))
        cameras(measured)
        // A finished catalogue would be reused as it is: an explicit run measures again.
        val resume = withContext(Dispatchers.IO) { store.loadFor(device.identity)?.complete != true }
        val vendor = if (experimental) experimentalVendor else NoVendorExtension
        val engine =
            ProbeEngine(runner, store, AndroidCapabilities.thermalMonitor(app), AndroidCapabilities.clock, vendor = vendor, direct = experimental)
        // Every run from this screen is one the user asked for: what hung before is measured again (D100).
        return AutoConfigRun(engine.run(device, ids, resume, listener, retryHangs = true), measured)
    }
}

/**
 * The OBS-style auto-configuration screen (D42): runs [session] (the real Camera2 probes on the main back and front cameras), shows
 * every result as it comes, and the best mode found. The results land in the capability store, where the camera screen
 * reads them ([ProbedLadder]). Leaving the screen cancels the run (the engine keeps what it measured so far, and the
 * next run resumes there).
 */
class AutoConfigViewModel internal constructor(
    private val settings: SettingsRepository,
    private val session: AutoConfigSession,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) : ViewModel() {
    constructor(application: Application, settings: SettingsRepository) : this(settings, Camera2AutoConfigSession(application))

    private val _state = MutableStateFlow(AutoConfigUiState())
    val state: StateFlow<AutoConfigUiState> = _state.asStateFlow()
    private var job: Job? = null

    @Suppress("TooGenericExceptionCaught") // Anything the platform throws ends the run with a message, not a crash.
    fun start() {
        if (job?.isActive == true) return
        val startedAt = elapsedRealtime()
        _state.value = AutoConfigUiState(phase = AutoConfigPhase.RUNNING)
        job = viewModelScope.launch {
            val ticker = launch {
                while (isActive) {
                    _state.update { it.copy(elapsedMs = elapsedRealtime() - startedAt) }
                    delay(TICK_MS)
                }
            }
            try {
                // Vendor sensor HDR variants and direct 8K are probed only with the Experimental switch on (D37, D63, D71).
                val experimental = (settings as? HdrRepository)?.hdrPrefs?.first()?.experimental == true
                val (run, cameras) = session.run(
                    experimental,
                    { cameras -> _state.update { it.copy(cameras = cameras) } },
                    ProbeListener { done, total, result -> onResult(done, total, result) }
                )
                ticker.cancel()
                _state.update {
                    it.copy(
                        phase = AutoConfigPhase.DONE,
                        stopReason = run.stopReason,
                        best = bestOf(run.capabilities, cameras.main),
                        frontBest = cameras.front?.let { bestOf(run.capabilities, it) },
                        cameras = cameras,
                        elapsedMs = run.elapsedMs,
                        vendorFamilies = run.capabilities.camera(cameras.main)?.advertised?.let(VendorKeyFamilies::of).orEmpty()
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = AutoConfigPhase.IDLE) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = AutoConfigPhase.FAILED, error = "${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                ticker.cancel()
            }
        }
    }

    private fun onResult(done: Int, total: Int, result: ProbeResult) = _state.update {
        it.copy(done = done, total = total, rows = it.rows + ProbeRow(result))
    }

    /** Stops a run in progress (leaving the screen, app in the background). */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Run or skipped: not offered again at start. */
    fun markOffered() = viewModelScope.launch { settings.setAutoConfigOffered(true) }

    private companion object {
        const val TICK_MS = 250L

        fun bestOf(capabilities: DeviceCapabilities, cameraId: String): RecordingProfile? = ProbedLadder.profiles(capabilities, cameraId).firstOrNull()
    }
}
