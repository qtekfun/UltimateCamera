package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
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
    val stabilization: Boolean = false
)

/**
 * One camera streaming into one surface (the GPU stage) for as long as it is open. The repeating request is also the
 * session parameters, so the HAL picks a sensor mode for the target frame rate at configure time (spike finding: without
 * them the Pixel 8 configured 30 fps and closed the camera on a 60 fps request).
 */
class CameraStream private constructor(private val device: CameraDevice, private val session: CameraCaptureSession) {
    /** Stops streaming and closes the camera. */
    fun close() {
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        device.close()
    }

    companion object {
        /**
         * Opens [spec]'s camera and streams into [target]. Throws [SessionRejectedException] when the HAL refuses the
         * configuration and [CameraLostException] when the camera cannot be opened; [onLost] reports a later loss.
         */
        suspend fun open(manager: CameraManager, spec: StreamSpec, target: Surface, handler: Handler, onLost: (String) -> Unit): CameraStream {
            val chars = manager.getCameraCharacteristics(spec.cameraId)
            val device = manager.openCameraDevice(spec.cameraId, handler, onLost)
            var opened = false
            try {
                val output = OutputConfiguration(target).apply { spec.dynamicRangeProfile?.let { dynamicRangeProfile = it } }
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(target)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange(chars, spec.fps))
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        if (spec.stabilization) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                }.build()
                val executor = Executor { handler.post(it) }
                val session = device.createSession(listOf(output), executor, sessionParameters = request)
                session.setRepeatingRequest(request, null, handler)
                opened = true
                return CameraStream(device, session)
            } finally {
                // Whatever failed (HAL refusal, cancellation, vendor exception), the device must not stay open.
                if (!opened) device.close()
            }
        }

        /** The fixed range [fps, fps] when the camera lists it, else any range reaching [fps]; [fps, fps] as a try otherwise. */
        fun fpsRange(chars: CameraCharacteristics, fps: Int): Range<Int> {
            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            return ranges.firstOrNull { it.lower == fps && it.upper == fps } ?: ranges.firstOrNull { it.upper == fps } ?: Range(fps, fps)
        }

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
