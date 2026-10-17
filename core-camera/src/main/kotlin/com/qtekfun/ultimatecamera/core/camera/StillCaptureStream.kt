package com.qtekfun.ultimatecamera.core.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A photo session (decision D41): an 8-bit preview into [previewTarget] (the GPU stage) plus a JPEG [ImageReader] at
 * full size. The preview runs with continuous-picture AF; [capture] runs the AF/AE precapture sequence when the camera
 * supports it (bounded by a timeout, so a HAL that never converges still takes the picture) and then one
 * `TEMPLATE_STILL_CAPTURE` request. One capture at a time; call from one coroutine.
 */
class StillCaptureStream private constructor(
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
    private val reader: ImageReader,
    private val previewTarget: Surface,
    private val handler: Handler,
    private val autoFocus: Boolean,
    private val precapture: Boolean
) : StillSource {
    private val images = Channel<Image>(Channel.UNLIMITED)
    private val meter = MutableStateFlow(Meter(-1, null, null))

    private val tracker = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            meter.value = Meter(result.frameNumber, result.get(CaptureResult.CONTROL_AF_STATE), result.get(CaptureResult.CONTROL_AE_STATE))
        }
    }

    init {
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { if (images.trySend(it).isFailure) it.close() } }, handler)
    }

    private fun startPreview() {
        session.setRepeatingRequest(previewRequest().build(), tracker, handler)
    }

    private fun previewRequest(): CaptureRequest.Builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(previewTarget)
        if (autoFocus) set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }

    /**
     * Takes one JPEG rotated by [jpegOrientation] degrees and hands its bytes to [consume] (the buffer is valid only
     * during the call; the image is closed afterwards). Throws on a camera failure or when no image arrives in time.
     */
    override suspend fun <T> capture(jpegOrientation: Int, quality: Int, consume: (ByteBuffer) -> T): T {
        drainImages() // Stale image of a capture that timed out.
        val triggered = if (autoFocus || precapture) runPrecapture() else false
        try {
            val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                addTarget(previewTarget)
                if (autoFocus) set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                set(CaptureRequest.JPEG_QUALITY, quality.toByte())
            }.build()
            session.capture(still, null, handler)
            val image = withTimeout(IMAGE_TIMEOUT_MS) { images.receive() }
            return image.use { consume(it.planes[0].buffer) }
        } finally {
            // Back to continuous AF after a trigger: the lens otherwise stays locked on the last subject.
            if (triggered) runCatching { cancelAfTrigger() }
        }
    }

    private fun cancelAfTrigger() {
        val cancel = previewRequest().apply { set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL) }.build()
        session.capture(cancel, tracker, handler)
    }

    private fun drainImages() {
        while (true) {
            val stale = images.tryReceive().getOrNull() ?: return
            stale.close()
        }
    }

    /** Sends the AF and AE precapture triggers and waits (bounded) until both settle; true when AF was triggered. */
    private suspend fun runPrecapture(): Boolean {
        val trigger = previewRequest().apply {
            if (autoFocus) set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            if (precapture) set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        }.build()
        val from = meter.value.frame + 1
        session.capture(trigger, tracker, handler)
        var sawPrecapture = false
        withTimeoutOrNull(PRECAPTURE_TIMEOUT_MS) {
            meter.first { m ->
                if (m.aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) sawPrecapture = true
                m.frame >= from && afSettled(m.afState) && aeSettled(m.aeState, sawPrecapture || m.frame >= from + AE_GRACE_FRAMES)
            }
        }
        return autoFocus
    }

    private fun afSettled(state: Int?): Boolean = !autoFocus || state == null ||
        state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED

    private fun aeSettled(state: Int?, sequenceRan: Boolean): Boolean = !precapture || state == null ||
        (
            sequenceRan &&
                (
                    state == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        state == CaptureResult.CONTROL_AE_STATE_LOCKED
                    )
            )

    /** Stops the preview and closes the camera and the JPEG reader. */
    override fun close() {
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        device.close()
        reader.close()
        drainImages()
        images.close()
    }

    private data class Meter(val frame: Long, val afState: Int?, val aeState: Int?)

    companion object {
        const val DEFAULT_QUALITY = 95
        const val PRECAPTURE_TIMEOUT_MS = 1_500L
        const val IMAGE_TIMEOUT_MS = 5_000L

        /** Frames after the trigger after which AE counts as settled even if it never reported PRECAPTURE. */
        private const val AE_GRACE_FRAMES = 3

        /** The still ([format]: JPEG or JPEG_R) and preview sizes this camera offers for [PhotoSizes.choose]. */
        fun sizes(chars: CameraCharacteristics, format: Int = ImageFormat.JPEG): PhotoSizes? {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val jpeg = map.getOutputSizes(format).orEmpty().map { PixelSize(it.width, it.height) }
            val preview = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { PixelSize(it.width, it.height) }
            return PhotoSizes.choose(jpeg, preview)
        }

        /**
         * Opens [cameraId] for photos: preview into [previewTarget] (sized [PhotoSizes.preview] by the caller) and JPEG
         * at [jpeg] in [format] (JPEG, or JPEG_R for Ultra HDR, D64, produced from an HLG10 output). Throws
         * [SessionRejectedException] when the HAL refuses the outputs and [CameraLostException] when the camera cannot
         * be opened; [onLost] reports a later loss.
         */
        suspend fun open(
            manager: CameraManager,
            cameraId: String,
            previewTarget: Surface,
            jpeg: PixelSize,
            handler: Handler,
            format: Int = ImageFormat.JPEG,
            onLost: (String) -> Unit
        ): StillCaptureStream {
            val chars = manager.getCameraCharacteristics(cameraId)
            val reader = ImageReader.newInstance(jpeg.width, jpeg.height, format, MAX_IMAGES)
            var device: CameraDevice? = null
            var opened = false
            try {
                device = manager.openCameraDevice(cameraId, handler, onLost)
                val still = OutputConfiguration(reader.surface)
                if (format != ImageFormat.JPEG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    still.dynamicRangeProfile = DynamicRangeProfiles.HLG10
                }
                val outputs = listOf(OutputConfiguration(previewTarget), still)
                val session = device.createSession(outputs, Executor { handler.post(it) })
                val stream = StillCaptureStream(device, session, reader, previewTarget, handler, autoFocus(chars), precaptureSupported(chars))
                stream.startPreview()
                opened = true
                return stream
            } finally {
                if (!opened) {
                    device?.close()
                    reader.close()
                }
            }
        }

        private const val MAX_IMAGES = 2

        private fun autoFocus(chars: CameraCharacteristics): Boolean {
            val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return false
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            return CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes && minFocus > 0f
        }

        /** LEGACY HALs emulate the precapture trigger badly; every other level supports it. */
        private fun precaptureSupported(chars: CameraCharacteristics): Boolean =
            chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
    }
}
