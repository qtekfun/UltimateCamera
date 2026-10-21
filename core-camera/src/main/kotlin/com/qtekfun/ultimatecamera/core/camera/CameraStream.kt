package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.media.AudioTimestamp
import android.os.Handler
import android.util.Range
import android.view.Surface
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** What a [CameraStream] delivers: size and frame rate, and the 10-bit profile (null = 8-bit SDR). */
data class StreamSpec(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val dynamicRangeProfile: Long? = null,
    /** Electronic stabilisation (REQ-CAM-003); a mode the camera does not offer is sent as OFF. */
    val stabilization: VideoStabilization = VideoStabilization.OFF,
    /** Vendor operating mode for `SessionConfiguration` (e.g. Qualcomm ZZHDR `0xF002`); null = regular session. */
    val sessionType: Int? = null,
    /**
     * Tag the output feeding the encoder (the record output in direct mode, else the target) with the `VIDEO_RECORD`
     * stream use case (D104). Only for a camera listing `REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE`; false leaves
     * the platform's default.
     */
    val recordUseCase: Boolean = false
)

/**
 * [frames] capture results so far, the last stamped [lastTimestampNs] (-1 before the first), [skipped] the frames the
 * camera itself left out between them ([skippedIn]), and [buffersLost] the output buffers it reported lost: one per
 * `onCaptureBufferLost`, plus one per whole capture it failed (`onCaptureFailed`, flushed ones aside), combined as both
 * are a frame the HAL could not fill (D104).
 */
data class FrameClock(val frames: Int, val lastTimestampNs: Long, val skipped: Int = 0, val buffersLost: Int = 0) {
    /** Frames per second the camera produced between [start] and this reading; null when the span is empty. */
    fun fpsSince(start: FrameClock): Double? {
        val frames = frames - start.frames
        val spanNs = lastTimestampNs - start.lastTimestampNs
        if (start.lastTimestampNs < 0 || frames <= 0 || spanNs <= 0) return null
        return frames * NANOS_PER_SECOND / spanNs
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** A failed capture counts as lost unless it was only flushed (a session closing or aborting its requests). */
        fun lostOnFailure(reason: Int): Boolean = reason != CaptureFailure.REASON_FLUSHED

        /** A step longer than this many frame intervals between two capture results has frames missing. */
        private const val GAP_INTERVALS = 1.5

        /**
         * Frames missing in a [stepNs] step between two sensor timestamps at [fps]: 0 for a regular step, else the
         * whole frame intervals beyond the first. In low light a sensor may stretch a single frame now and then
         * (measured at 60 fps: a few 33 ms steps in 20 s, none in daylight), which no app can avoid.
         */
        fun skippedIn(stepNs: Long, fps: Int): Int {
            if (fps <= 0 || stepNs <= 0) return 0
            val intervals = stepNs * fps / NANOS_PER_SECOND
            return if (intervals > GAP_INTERVALS) (intervals.roundToInt() - 1).coerceAtLeast(1) else 0
        }
    }
}

/**
 * One camera streaming into one surface (the GPU stage) for as long as it is open. The repeating request is also the
 * session parameters, so the HAL picks a sensor mode for the target frame rate at configure time (spike finding: without
 * them the Pixel 8 configured 30 fps and closed the camera on a 60 fps request).
 */
class CameraStream private constructor(
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
    private val spec: StreamSpec,
    private val handler: Handler,
    private val baseRequest: () -> CaptureRequest.Builder,
    chars: CameraCharacteristics,
    private val recording: RecordTarget?,
    private val depth: VideoDepthOutputs? = null,
    /** The AE target range the session was opened with ([fpsRangeCandidates]). */
    private val fpsRange: IntRange = spec.fps..spec.fps
) {
    /** What this camera lets the user control (REQ-CAM-002). */
    val ranges: ControlRanges = Camera2Controls.ranges(chars)
    private val calibrations = if (ranges.kelvinRange != null) Camera2Controls.calibrations(chars) else null

    /** Scene white balance for AUTO (null when the camera cannot take manual gains: its own AWB is used then). */
    private val sceneAwb = if (ranges.manualWhiteGains) SceneAwb(calibrations?.let { SceneAwb.locusBounds(it.first, it.second) }) else null

    /** Idle standby (D97): the AE range of [StandbyRate], null when this stream has none. */
    private val standbyRange = StandbyRate.range(
        spec.fps,
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { it.lower..it.upper },
        vendorSession = spec.sessionType != null
    )

    /** In idle standby now ([setStandby]). */
    @Volatile var standby = false
        private set

    /** The rate the camera runs at: [StandbyRate.STANDBY_FPS] in standby, the mode's otherwise. */
    val fps: Int get() = if (standby) StandbyRate.STANDBY_FPS else spec.fps

    /** Where the renderer sends its scene colour measurements ([sceneColor]); null when they are not used. */
    val sceneColorSink: ((Float, Float) -> Unit)? get() = if (sceneAwb != null) ::sceneColor else null

    /** Receives what the camera is doing (ISO, shutter, focus), about [LIVE_INTERVAL_NS] apart, on the camera thread. */
    @Volatile var onLive: ((LiveExposure) -> Unit)? = null

    // Touched only on the camera thread (the handler).
    private var controls: CameraControls? = null
    private var crop: SensorRect = ranges.activeArray
    private var autoTransform: ColorSpaceTransform? = null
    private var resultGains: RggbChannelVector? = null
    private var autoGains: RggbChannelVector? = null
    private var lastLiveNanos = 0L

    /** The physical lens in use and when to copy the camera's white balance again after a switch (D113). */
    private val lens = LensWatch()
    private val awbLatch = AwbLatch()

    init {
        DiagnosticLog.log(TAG) {
            "open ${spec.cameraId} ${spec.width}x${spec.height}@${spec.fps} range=$fpsRange profile=${spec.dynamicRangeProfile} " +
                "eis=${spec.stabilization} session=${spec.sessionType} sceneAwb=${sceneAwb != null} calibrated=${calibrations != null}"
        }
    }

    /** Capture results seen, the sensor timestamp of the last one and the frames skipped, read together by [frameClock]. */
    private var resultCount = 0
    private var lastSensorTimestampNs = -1L
    private var skippedFrames = 0
    private var lostBuffers = 0
    private val clockLock = Any()

    /** How many frames the camera has produced and the sensor timestamp of the last, for measuring its real rate. */
    fun frameClock(): FrameClock = synchronized(clockLock) { FrameClock(resultCount, lastSensorTimestampNs, skippedFrames, lostBuffers) }

    private val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = onResult(result)

        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) = countLost()

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            if (FrameClock.lostOnFailure(failure.reason)) countLost()
        }
    }

    private fun countLost() {
        synchronized(clockLock) { lostBuffers++ }
    }

    /**
     * Replaces the repeating request with [requested] (clamped to [ranges]) without reconfiguring the session: the
     * session-parameter keys (frame rate, stabilisation) stay those of [open]. AF lock and a new focus point are
     * applied with a one-off trigger capture.
     */
    fun setControls(requested: CameraControls) {
        handler.post {
            // A session closed meanwhile (profile change, app stopped) throws; the next stream gets the controls again.
            runCatching { applyControls(ranges.clamp(requested, spec.fps)) }
        }
    }

    private fun applyControls(c: CameraControls) {
        val previous = controls
        controls = c
        val builder = request()
        if (c.whiteBalance != WhiteBalance.AUTO) sceneAwb?.reset()
        if (previous?.let { listOf(it.whiteBalance, it.zoomRatio, it.awbLock) } != listOf(c.whiteBalance, c.zoomRatio, c.awbLock)) {
            DiagnosticLog.log(TAG) { "controls wb=${c.whiteBalance} kelvin=${c.kelvin} awbLock=${c.awbLock} zoom=${c.zoomRatio}" }
        }
        // In standby a manual exposure's frame duration follows the standby rate (the shutter already fits it).
        val rate = if (standby) spec.copy(fps = StandbyRate.STANDBY_FPS) else spec
        Camera2Controls.apply(builder, c, ranges, rate, crop, autoTransform, calibrations, sceneAwb?.gains)
        val trigger = afTrigger(previous, c, ranges)
        if (trigger != null) {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            session.capture(builder.build(), results, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        }
        repeat(builder)
    }

    /** The base request, with the standby frame rate while in [standby]. */
    private fun request(): CaptureRequest.Builder = baseRequest().apply {
        val range = standbyRange
        if (standby && range != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(range.first, range.last))
    }

    /** Sends the current controls again (or the bare request before any). */
    private fun refresh() {
        handler.post {
            runCatching {
                val c = controls
                if (c != null) applyControls(c) else repeat(request())
            }
        }
    }

    /**
     * Idle standby (D97): [on] runs the camera at [StandbyRate.STANDBY_FPS] without reconfiguring the session, off
     * brings the mode's rate back (wait for it with [awaitFullRate]). True when the state changed; a stream without a
     * standby range never enters it.
     */
    fun setStandby(on: Boolean): Boolean {
        val next = on && standbyRange != null
        if (next == standby) return false
        standby = next
        refresh()
        return true
    }

    /** Suspends until the camera delivers the mode's frame rate again (at most [StandbyRate.SETTLE_TIMEOUT_MS]). */
    suspend fun awaitFullRate() {
        if (!StandbyRate.waitsForFullRate(fpsRange, spec.fps)) {
            delay(StandbyRate.SAMPLE_MS)
            return
        }
        var waited = 0L
        var from = frameClock()
        while (waited < StandbyRate.SETTLE_TIMEOUT_MS) {
            delay(StandbyRate.SAMPLE_MS)
            waited += StandbyRate.SAMPLE_MS
            val to = frameClock()
            if (StandbyRate.atFullRate(to.fpsSince(from), spec.fps)) return
            from = to
        }
    }

    /**
     * Makes [builder] the repeating request; with depth outputs for portrait video (D89) a repeating burst in which one
     * request in [VideoDepthOutputs.every] also fills them ([LiveDepthBurst]), so the depth costs the camera little.
     */
    private fun repeat(builder: CaptureRequest.Builder) {
        val d = depth
        if (d == null) {
            session.setRepeatingRequest(builder.build(), results, handler)
            return
        }
        val plain = builder.build()
        d.surfaces.forEach(builder::addTarget)
        val withDepth = builder.build()
        d.surfaces.forEach(builder::removeTarget)
        session.setRepeatingBurst(LiveDepthBurst.pattern(d.every).map { if (it) withDepth else plain }, results, handler)
    }

    private fun onResult(result: TotalCaptureResult) {
        result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
            synchronized(clockLock) {
                if (lastSensorTimestampNs >= 0) skippedFrames += FrameClock.skippedIn(ts - lastSensorTimestampNs, fps)
                resultCount++
                lastSensorTimestampNs = ts
            }
        }
        result.get(CaptureResult.SCALER_CROP_REGION)?.let { crop = SensorRect(it.left, it.top, it.width(), it.height()) }
        val physical = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        val before = lens.lens
        if (lens.switched(physical)) relatchWhiteBalance("lens $before -> $physical")
        val awbMode = result.get(CaptureResult.CONTROL_AWB_MODE)
        val converged = result.get(CaptureResult.CONTROL_AWB_STATE) == CameraMetadata.CONTROL_AWB_STATE_CONVERGED
        if (awbMode != null && awbMode != CameraMetadata.CONTROL_AWB_MODE_OFF && awbLatch.copies(converged)) {
            val first = autoTransform == null
            // The automatic colour matrix is reused for manual Kelvin: only the gains change, colours stay the camera's.
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { autoTransform = it }
            // And its gains: the scene white balance starts from them and stays near them.
            result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { autoGains = it }
            if (first) DiagnosticLog.log(TAG) { "awb copied lens=${lens.lens} gains=${autoGains?.let(::gainsText)} converged=$converged" }
        }
        result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { resultGains = it }
        val listener = onLive ?: return
        val now = System.nanoTime()
        if (now - lastLiveNanos < LIVE_INTERVAL_NS) return
        lastLiveNanos = now
        listener(
            liveExposure(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                afState = result.get(CaptureResult.CONTROL_AF_STATE)
            )
        )
    }

    /**
     * One measurement of the picture's remaining cast ([red] and [blue] of its illuminant over green, from the whole
     * frame): in AUTO, unlocked, it moves the scene white balance ([SceneAwb]). Any thread.
     */
    fun sceneColor(red: Float, blue: Float) {
        val awb = sceneAwb ?: return
        handler.post {
            val c = controls ?: return@post
            val gains = resultGains ?: return@post
            // The first gains need the camera's colour matrix and its own gains, which only its AWB reports.
            val anchor = autoGains ?: return@post
            if (c.whiteBalance != WhiteBalance.AUTO || c.awbLock) return@post
            if (autoTransform == null || awbLatch.settling) return@post
            val next = awb.update(gains.normalised(), anchor.normalised(), red, blue)
            DiagnosticLog.log(TAG) {
                "scene lens=${lens.lens} cast r=%.3f b=%.3f gains=%s -> %s".format(
                    Locale.ROOT,
                    red,
                    blue,
                    gainsText(gains),
                    next?.let { "%.3f/%.3f".format(Locale.ROOT, it.red, it.blue) } ?: "same"
                )
            }
            if (next != null) runCatching { applyControls(c) }
        }
    }

    /**
     * The logical camera switched to another lens (D113): the white balance copied from the previous one no longer fits,
     * so the scene white balance starts again from the camera's own AWB on the new lens, once it has settled. Camera thread.
     */
    private fun relatchWhiteBalance(reason: String) {
        DiagnosticLog.log(TAG) { "$reason: white balance copied again" }
        val awb = sceneAwb ?: return
        awb.reset()
        autoTransform = null
        autoGains = null
        awbLatch.restart()
        // Without a matrix the request is the camera's AWB again ([SceneAwb.manualGains]) until the copy is made.
        controls?.let { runCatching { applyControls(it) } }
    }

    private fun gainsText(g: RggbChannelVector): String = "%.3f/%.3f/%.3f".format(Locale.ROOT, g.red, (g.greenEven + g.greenOdd) / 2f, g.blue)

    /**
     * Direct mode (D71): starts or stops sending frames to the record output given to [open] (the encoder's surface),
     * without reconfiguring the session. False when the stream has no record output.
     */
    fun setRecording(on: Boolean): Boolean {
        val target = recording ?: return false
        target.active = on
        refresh()
        return true
    }

    /** The gains with green as 1, as [KelvinMath] gives them. */
    private fun RggbChannelVector.normalised(): RggbGains {
        val g = (greenEven + greenOdd) / 2f
        return if (g > 0f) RggbGains(red / g, 1f, 1f, blue / g) else RggbGains(red, greenEven, greenOdd, blue)
    }

    /** An output that is in the session from the start but only targeted while [active]. */
    private class RecordTarget(val surface: Surface) {
        @Volatile var active = false
    }

    /** Stops streaming and closes the camera. */
    fun close() {
        DiagnosticLog.log(TAG) { "close ${spec.cameraId}" }
        onLive = null
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        device.close()
        depth?.close()
    }

    companion object {
        /** Live values are reported at about 10 Hz: enough for the readouts, cheap on modest phones. */
        const val LIVE_INTERVAL_NS = 100_000_000L

        /** `SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD`, as `OutputConfiguration.setStreamUseCase` takes it. */
        private const val TAG = "camera"
        private const val RECORD_USE_CASE = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()

        /**
         * Opens [spec]'s camera and streams into [target]. Throws [SessionRejectedException] when the HAL refuses the
         * configuration and [CameraLostException] when the camera cannot be opened; [onLost] reports a later loss.
         * [extras] sets more keys (e.g. vendor tags) on the request, which is also the session parameters: every
         * repeating request starts from the same base, so a per-request vendor key stays on each frame (D104).
         */
        suspend fun open(
            manager: CameraManager,
            spec: StreamSpec,
            target: Surface,
            handler: Handler,
            extras: (CaptureRequest.Builder) -> Unit = {},
            /** Direct mode (D71): the encoder's surface, in the session from the start, targeted only while recording. */
            recordOutput: Surface? = null,
            /** Portrait video (D89): depth outputs in the session, filled by one request in a few; closed with the stream. */
            depth: VideoDepthOutputs? = null,
            onLost: (String) -> Unit
        ): CameraStream {
            val chars = manager.getCameraCharacteristics(spec.cameraId)
            val device = manager.openCameraDevice(spec.cameraId, handler, onLost)
            var opened = false
            try {
                val encoderFeed = recordOutput ?: target
                val outputs = listOfNotNull(target, recordOutput).map { surface ->
                    OutputConfiguration(surface).apply {
                        spec.dynamicRangeProfile?.let { dynamicRangeProfile = it }
                        // Front cameras are mirrored by default; the recording must not be (the preview mirrors on the GPU).
                        mirrorMode = OutputConfiguration.MIRROR_MODE_NONE
                        if (spec.recordUseCase && surface === encoderFeed) streamUseCase = RECORD_USE_CASE
                    }
                } + depth?.configurations().orEmpty()
                val record = recordOutput?.let(::RecordTarget)
                val stabilization = spec.stabilization.takeIf { it in Camera2Controls.stabilizations(chars) } ?: VideoStabilization.OFF
                val advertised = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { it.lower..it.upper }
                val candidates = fpsRangeCandidates(advertised, spec.fps)
                val executor = Executor { handler.post(it) }
                return firstAccepted(candidates) { range ->
                    val fps = Range(range.first, range.last)
                    // Every request starts from these keys, so later control changes never touch the session parameters.
                    val base = {
                        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(target)
                            if (record?.active == true) addTarget(record.surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, Camera2Controls.stabilizationMode(stabilization))
                            extras(this)
                        }
                    }
                    val request = base().build()
                    val session = device.createSession(outputs, executor, sessionParameters = request, sessionType = spec.sessionType)
                    val stream = CameraStream(device, session, spec, handler, base, chars, record, depth, range)
                    stream.repeat(base())
                    opened = true
                    stream
                }
            } finally {
                // Whatever failed (HAL refusal, cancellation, vendor exception), the device must not stay open.
                if (!opened) device.close()
            }
        }

        /**
         * [attempt] on each candidate in turn until one is not refused by the HAL ([SessionRejectedException]); the
         * last refusal is thrown. Used to fall back from an unadvertised fixed AE range on the same open device.
         */
        internal suspend fun <T, R> firstAccepted(candidates: List<T>, attempt: suspend (T) -> R): R {
            var refusal: SessionRejectedException? = null
            for (candidate in candidates) {
                try {
                    return attempt(candidate)
                } catch (e: SessionRejectedException) {
                    refusal = e
                }
            }
            throw refusal ?: SessionRejectedException("nothing to try")
        }

        /**
         * The one-off `CONTROL_AF_TRIGGER` a change from [previous] to [next] needs, or null: only with continuous
         * autofocus (a focusing lens, not manual focus), when the AF lock or the tap point changed. START locks AF where
         * it is; CANCEL makes it scan again (on the new point, if any).
         */
        fun afTrigger(previous: CameraControls?, next: CameraControls, ranges: ControlRanges): Int? {
            val autofocus = !next.manualFocus && ranges.minFocusDiopters > 0f
            val lockChanged = (previous?.afLock ?: false) != next.afLock
            val pointChanged = previous?.meteringPoint != next.meteringPoint
            return when {
                !autofocus || !(lockChanged || pointChanged) -> null
                next.afLock -> CameraMetadata.CONTROL_AF_TRIGGER_START
                else -> CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            }
        }

        /** The readout of one capture result: AE locked only in LOCKED, AF locked in either locked state. */
        fun liveExposure(iso: Int?, exposureTimeNs: Long?, focusDiopters: Float?, aeState: Int?, afState: Int?): LiveExposure = LiveExposure(
            iso = iso,
            exposureTimeNs = exposureTimeNs,
            focusDiopters = focusDiopters,
            aeLocked = aeState == CameraMetadata.CONTROL_AE_STATE_LOCKED,
            afLocked = afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        )

        /**
         * AE target ranges to try for [fps], in order. Always the fixed range [fps, fps] first, listed or not: a
         * variable range such as [30, 60] lets AE stretch the exposure and halve the rate indoors (the vivo X500 Pro
         * Max probe ran "1080p60" at 30.0 fps that way). Then the listed ranges reaching [fps], tightest first, only
         * for a HAL that refuses the unlisted fixed one.
         */
        fun fpsRangeCandidates(advertised: List<IntRange>, fps: Int): List<IntRange> =
            (listOf(fps..fps) + advertised.filter { it.last == fps && it.first < fps }.sortedByDescending { it.first }).distinct()

        /**
         * The `AudioTimestamp` timebase that matches this camera's frame timestamps, so audio and video share a clock:
         * REALTIME sensors stamp in the boot-time clock, UNKNOWN ones in the monotonic clock (`System.nanoTime`).
         */
        fun audioTimebase(chars: CameraCharacteristics): Int =
            if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                AudioTimestamp.TIMEBASE_BOOTTIME
            } else {
                AudioTimestamp.TIMEBASE_MONOTONIC
            }

        /** HLG10 when the camera offers it (the profile the spike validated on both phones), else the first 10-bit one. */
        fun tenBitProfile(chars: CameraCharacteristics): Long? =
            tenBitProfile(chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty())

        /** [tenBitProfile] among the `DynamicRangeProfiles` constants in [profiles]: HLG10, then HDR10, then HDR10+. */
        fun tenBitProfile(profiles: Set<Long>): Long? =
            listOf(DynamicRangeProfiles.HLG10, DynamicRangeProfiles.HDR10, DynamicRangeProfiles.HDR10_PLUS).firstOrNull { it in profiles }

        /** The clock the camera stamps frames in, as `System.nanoTime`-compatible "now" for this stream. */
        fun nowNanos(chars: CameraCharacteristics): Long =
            if (audioTimebase(chars) == AudioTimestamp.TIMEBASE_BOOTTIME) android.os.SystemClock.elapsedRealtimeNanos() else System.nanoTime()
    }
}
