package com.qtekfun.ultimatecamera.core.capabilities

/** One short run the engine asks the runner for. */
data class ProbeRequest(
    val cameraId: String,
    val config: VideoConfig,
    val warmupMs: Long,
    val measureMs: Long,
    /** Extra session parameters from a [VendorExtension]; empty for a plain Camera2 session. */
    val vendorParameters: List<VendorParameter> = emptyList(),
    /** Vendor operating mode ([VendorExtension.sessionType]); null for a regular session. */
    val sessionType: Int? = null
)

/** How far the runner got with the session. */
enum class SessionStatus {
    /** The session ran and [ProbeMeasurement] holds the numbers. */
    RAN,
    SESSION_REJECTED,
    CAMERA_ERROR,
    ENCODER_UNSUPPORTED,

    /** Set by the engine's watchdog, never returned by a runner. */
    TIMEOUT
}

/** Raw numbers of one run. The engine, not the runner, decides whether they meet the target (one criterion, D10). */
data class ProbeMeasurement(
    val status: SessionStatus,
    /** Rate of the frames that reached the encoder over the measured window. */
    val measuredFps: Double = 0.0,
    /** Frames the GPU and the encoder lost; the camera's own skips ([cameraSkips]) are not counted (D98). */
    val droppedFrames: Int = 0,
    /**
     * Rate the camera itself produced frames at (its capture results' sensor timestamps), null when not measured.
     * Tells a camera running slower than asked (e.g. AE stretching the exposure) from a pipeline losing frames.
     */
    val cameraFps: Double? = null,
    val thermalStatusMax: Int = ThermalStatus.NONE,
    val error: String? = null,
    /** Gaps (steps of more than 1.5 frame intervals) between consecutive encoded frames in the window; null when not measured. */
    val gapCount: Int? = null,
    /** Largest step between two consecutive encoded frames in the window, in ms; null when not measured. */
    val maxGapMs: Double? = null,
    /** Frames the camera itself left out in the window (a frame stretched in low light); null when not measured. */
    val cameraSkips: Int? = null
)

/**
 * Runs one configuration for real: Camera2 session (with the size, rate, dynamic range profile and the request passed
 * as session parameters) → GL (LUT pass per [VideoConfig.lut]) → MediaCodec surface, for [ProbeRequest.warmupMs] +
 * [ProbeRequest.measureMs], then releases everything. The spike's `PipelineTest` is the reference implementation.
 *
 * Contract:
 * - Never trust StreamConfigurationMap for the rate: build the session even when the size is advertised at a lower
 *   rate (the OPPO CPH2841 lists 4K at 30 fps and holds 4K60 10-bit).
 * - Report a refused session as [SessionStatus.SESSION_REJECTED], a camera failure as [SessionStatus.CAMERA_ERROR], an
 *   encoder that cannot be configured as [SessionStatus.ENCODER_UNSUPPORTED]; throw only for cancellation.
 * - Stop early (and report what was measured) if the thermal status reaches [ThermalStatus.SEVERE].
 * - Write nothing to storage: the encoded output may go to a discarded temporary file or nowhere.
 * - Be cancellable: on cancellation release the camera, the GL context and the codec, then rethrow.
 */
fun interface ProbeRunner {
    suspend fun run(request: ProbeRequest): ProbeMeasurement
}

/** `PowerManager` thermal status values (THERMAL_STATUS_*), so the engine needs no Android types. */
object ThermalStatus {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
}

/** Current thermal status (`PowerManager.getCurrentThermalStatus()` on a phone). */
fun interface ThermalMonitor {
    fun status(): Int
}

/** Monotonic milliseconds (`SystemClock.elapsedRealtime()` on a phone, the virtual clock in tests). */
fun interface MonotonicClock {
    fun nowMs(): Long
}
