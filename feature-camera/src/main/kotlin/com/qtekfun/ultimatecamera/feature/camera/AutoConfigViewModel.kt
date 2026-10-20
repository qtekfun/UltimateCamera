package com.qtekfun.ultimatecamera.feature.camera

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.capabilities.AndroidCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.NoVendorExtension
import com.qtekfun.ultimatecamera.core.capabilities.ProbeEngine
import com.qtekfun.ultimatecamera.core.capabilities.ProbeListener
import com.qtekfun.ultimatecamera.core.capabilities.ProbeOutcome
import com.qtekfun.ultimatecamera.core.capabilities.ProbePlanner
import com.qtekfun.ultimatecamera.core.capabilities.ProbeResult
import com.qtekfun.ultimatecamera.core.capabilities.ProbeRun
import com.qtekfun.ultimatecamera.core.capabilities.SensorHdrExtension
import com.qtekfun.ultimatecamera.core.capabilities.StopReason
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
    val maxGapMs: Double? = null
) {
    constructor(result: ProbeResult) : this(
        result.config.label,
        result.outcome,
        result.measuredFps,
        result.droppedFrames,
        result.marginal,
        result.gapCount,
        result.maxGapMs
    )

    /** The largest gap is worth showing only for a run that lost frames in gaps. */
    val shownGapMs: Double? get() = maxGapMs?.takeIf { (gapCount ?: 0) > 0 && (droppedFrames ?: 0) > 0 }
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
    val error: String? = null
)

/** A finished auto-configuration run and the camera it measured. */
data class AutoConfigRun(val run: ProbeRun, val cameraId: String)

/** One auto-configuration run (the real Camera2 probes in the app, a fake in the tests). */
fun interface AutoConfigSession {
    /** Probes the main back camera; [experimental] adds the vendor sensor HDR and direct 8K variants (D37, D63, D71). */
    suspend fun run(experimental: Boolean, listener: ProbeListener): AutoConfigRun
}

/** [ProbeEngine] with the real [Camera2ProbeRunner] on the main back camera; results go to the capability store. */
class Camera2AutoConfigSession(private val app: Application) : AutoConfigSession {
    override suspend fun run(experimental: Boolean, listener: ProbeListener): AutoConfigRun {
        val store = AndroidCapabilities.store(app)
        val runner = Camera2ProbeRunner(app)
        val device = withContext(Dispatchers.Default) {
            runner.cleanUp()
            AdvertisedDeviceReader.read(app)
        }
        val cameraId = ProbePlanner.primaryCameraIds(device).firstOrNull() ?: error("no camera")
        // A finished catalogue would be reused as it is: an explicit run measures again.
        val resume = withContext(Dispatchers.IO) { store.loadFor(device.identity)?.complete != true }
        val vendor = if (experimental) SensorHdrExtension else NoVendorExtension
        val engine =
            ProbeEngine(runner, store, AndroidCapabilities.thermalMonitor(app), AndroidCapabilities.clock, vendor = vendor, direct = experimental)
        // Every run from this screen is one the user asked for: what hung before is measured again (D100).
        return AutoConfigRun(engine.run(device, listOf(cameraId), resume, listener, retryHangs = true), cameraId)
    }
}

/**
 * The OBS-style auto-configuration screen (D42): runs [session] (the real Camera2 probes on the main back camera), shows
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
                val (run, cameraId) = session.run(experimental, ProbeListener { done, total, result -> onResult(done, total, result) })
                ticker.cancel()
                _state.update {
                    it.copy(
                        phase = AutoConfigPhase.DONE,
                        stopReason = run.stopReason,
                        best = bestOf(run.capabilities, cameraId),
                        elapsedMs = run.elapsedMs
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
