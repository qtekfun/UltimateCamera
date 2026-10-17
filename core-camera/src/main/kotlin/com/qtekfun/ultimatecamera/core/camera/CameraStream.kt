package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.media.AudioTimestamp
import android.os.Handler
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor

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
    val sessionType: Int? = null
)

/** [frames] capture results so far, the last stamped [lastTimestampNs] (-1 before the first). */
data class FrameClock(val frames: Int, val lastTimestampNs: Long) {
    /** Frames per second the camera produced between [start] and this reading; null when the span is empty. */
    fun fpsSince(start: FrameClock): Double? {
        val frames = frames - start.frames
        val spanNs = lastTimestampNs - start.lastTimestampNs
        if (start.lastTimestampNs < 0 || frames <= 0 || spanNs <= 0) return null
        return frames * NANOS_PER_SECOND / spanNs
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
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
    private val recording: RecordTarget?
) {
    /** What this camera lets the user control (REQ-CAM-002). */
    val ranges: ControlRanges = Camera2Controls.ranges(chars)
    private val calibrations = if (ranges.kelvinRange != null) Camera2Controls.calibrations(chars) else null

    /** Receives what the camera is doing (ISO, shutter, focus), about [LIVE_INTERVAL_NS] apart, on the camera thread. */
    @Volatile var onLive: ((LiveExposure) -> Unit)? = null

    // Touched only on the camera thread (the handler).
    private var controls: CameraControls? = null
    private var crop: SensorRect = ranges.activeArray
    private var autoTransform: ColorSpaceTransform? = null
    private var lastLiveNanos = 0L

    /** Capture results seen and the sensor timestamp of the last one, read together by [frameClock]. */
    private var resultCount = 0
    private var lastSensorTimestampNs = -1L
    private val clockLock = Any()

    /** How many frames the camera has produced and the sensor timestamp of the last, for measuring its real rate. */
    fun frameClock(): FrameClock = synchronized(clockLock) { FrameClock(resultCount, lastSensorTimestampNs) }

    private val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) = onResult(result)
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
        val builder = baseRequest()
        Camera2Controls.apply(builder, c, ranges, spec, crop, autoTransform, calibrations)
        val autofocus = !c.manualFocus && ranges.minFocusDiopters > 0f
        val lockChanged = (previous?.afLock ?: false) != c.afLock
        val pointChanged = previous?.meteringPoint != c.meteringPoint
        if (autofocus && (lockChanged || pointChanged)) {
            // START locks continuous-video AF where it is; CANCEL makes it scan again (on the new point, if any).
            val trigger = if (c.afLock) CameraMetadata.CONTROL_AF_TRIGGER_START else CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            session.capture(builder.build(), results, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        }
        session.setRepeatingRequest(builder.build(), results, handler)
    }

    private fun onResult(result: TotalCaptureResult) {
        result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
            synchronized(clockLock) {
                resultCount++
                lastSensorTimestampNs = ts
            }
        }
        result.get(CaptureResult.SCALER_CROP_REGION)?.let { crop = SensorRect(it.left, it.top, it.width(), it.height()) }
        val awbMode = result.get(CaptureResult.CONTROL_AWB_MODE)
        if (awbMode != null && awbMode != CameraMetadata.CONTROL_AWB_MODE_OFF) {
            // The automatic colour matrix is reused for manual Kelvin: only the gains change, colours stay the camera's.
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { autoTransform = it }
        }
        val listener = onLive ?: return
        val now = System.nanoTime()
        if (now - lastLiveNanos < LIVE_INTERVAL_NS) return
        lastLiveNanos = now
        val af = result.get(CaptureResult.CONTROL_AF_STATE)
        listener(
            LiveExposure(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                aeLocked = result.get(CaptureResult.CONTROL_AE_STATE) == CameraMetadata.CONTROL_AE_STATE_LOCKED,
                afLocked = af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            )
        )
    }

    /**
     * Direct mode (D71): starts or stops sending frames to the record output given to [open] (the encoder's surface),
     * without reconfiguring the session. False when the stream has no record output.
     */
    fun setRecording(on: Boolean): Boolean {
        val target = recording ?: return false
        target.active = on
        handler.post {
            runCatching {
                val c = controls
                if (c != null) {
                    applyControls(c)
                } else {
                    session.setRepeatingRequest(baseRequest().build(), results, handler)
                }
            }
        }
        return true
    }

    /** An output that is in the session from the start but only targeted while [active]. */
    private class RecordTarget(val surface: Surface) {
        @Volatile var active = false
    }

    /** Stops streaming and closes the camera. */
    fun close() {
        onLive = null
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        device.close()
    }

    companion object {
        /** Live values are reported at about 10 Hz: enough for the readouts, cheap on modest phones. */
        const val LIVE_INTERVAL_NS = 100_000_000L

        /**
         * Opens [spec]'s camera and streams into [target]. Throws [SessionRejectedException] when the HAL refuses the
         * configuration and [CameraLostException] when the camera cannot be opened; [onLost] reports a later loss.
         * [extras] sets more keys (e.g. vendor tags) on the request, which is also the session parameters.
         */
        suspend fun open(
            manager: CameraManager,
            spec: StreamSpec,
            target: Surface,
            handler: Handler,
            extras: (CaptureRequest.Builder) -> Unit = {},
            /** Direct mode (D71): the encoder's surface, in the session from the start, targeted only while recording. */
            recordOutput: Surface? = null,
            onLost: (String) -> Unit
        ): CameraStream {
            val chars = manager.getCameraCharacteristics(spec.cameraId)
            val device = manager.openCameraDevice(spec.cameraId, handler, onLost)
            var opened = false
            try {
                val outputs = listOfNotNull(target, recordOutput).map { surface ->
                    OutputConfiguration(surface).apply {
                        spec.dynamicRangeProfile?.let { dynamicRangeProfile = it }
                        // Front cameras are mirrored by default; the recording must not be (the preview mirrors on the GPU).
                        mirrorMode = OutputConfiguration.MIRROR_MODE_NONE
                    }
                }
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
                    val stream = CameraStream(device, session, spec, handler, base, chars, record)
                    session.setRepeatingRequest(request, stream.results, handler)
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
        fun tenBitProfile(chars: CameraCharacteristics): Long? {
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty()
            return listOf(DynamicRangeProfiles.HLG10, DynamicRangeProfiles.HDR10, DynamicRangeProfiles.HDR10_PLUS).firstOrNull { it in profiles }
        }

        /** The clock the camera stamps frames in, as `System.nanoTime`-compatible "now" for this stream. */
        fun nowNanos(chars: CameraCharacteristics): Long =
            if (audioTimebase(chars) == AudioTimestamp.TIMEBASE_BOOTTIME) android.os.SystemClock.elapsedRealtimeNanos() else System.nanoTime()
    }
}
