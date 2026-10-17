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
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.pow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Our own HDR bracket (D64, for cameras without an HDR extension or JPEG_R): an 8-bit preview into [previewTarget]
 * plus a YUV_420_888 [ImageReader]. [bracket] takes one frame per EV offset from the same session, with manual
 * exposure when the camera has MANUAL_SENSOR (exposure time first, then ISO, from the preview's metered values) or
 * else AE compensation; each frame goes to the consumer as soon as it arrives (which writes it to disk and lets go).
 */
class BracketCaptureStream private constructor(
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
    private val reader: ImageReader,
    private val previewTarget: Surface,
    private val handler: Handler,
    private val chars: CameraCharacteristics
) {
    private val images = Channel<Image>(Channel.UNLIMITED)
    private val meter = MutableStateFlow(Metered(-1, null, null, null, null))
    private val manual = PhotoHdrProbe.manualSensor(chars)

    private val tracker = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            meter.value = Metered(
                result.frameNumber,
                result.get(CaptureResult.CONTROL_AE_STATE),
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                result.get(CaptureResult.SENSOR_SENSITIVITY),
                result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
            )
        }
    }

    init {
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { if (images.trySend(it).isFailure) it.close() } }, handler)
    }

    private fun preview(compensation: Int = 0) {
        session.setRepeatingRequest(previewRequest(compensation).build(), tracker, handler)
    }

    private fun previewRequest(compensation: Int): CaptureRequest.Builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(previewTarget)
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
    }

    /**
     * Takes one YUV frame per entry of [evs] (stops relative to the metered exposure) and hands each to [consume] with
     * its real exposure relative to the metered one (2^ev unless clamped); the image is closed after the call.
     * Returns those relative exposures in order.
     */
    suspend fun bracket(evs: List<Double>, consume: (index: Int, image: Image, relativeExposure: Double) -> Unit): List<Double> {
        while (true) images.tryReceive().getOrNull()?.close() ?: break
        return if (manual && meter.value.exposureNs != null && meter.value.iso != null) manualBracket(evs, consume) else compensationBracket(evs, consume)
    }

    private suspend fun manualBracket(evs: List<Double>, consume: (Int, Image, Double) -> Unit): List<Double> {
        val base = meter.value
        val t0 = base.exposureNs ?: error("no metered exposure")
        val iso0 = base.iso ?: error("no metered ISO")
        val tRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val plans = evs.map { ev ->
            val wanted = t0 * 2.0.pow(ev)
            val t = wanted.toLong().coerceIn(tRange?.lower ?: 1L, minOf(tRange?.upper ?: MAX_EXPOSURE_NS, MAX_EXPOSURE_NS))
            val iso = (iso0 * wanted / t).toInt().coerceIn(isoRange?.lower ?: iso0, isoRange?.upper ?: iso0)
            Triple(t, iso, (t.toDouble() * iso) / (t0.toDouble() * iso0))
        }
        val requests = plans.map { (t, iso, _) ->
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, t)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_FRAME_DURATION, t)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
        }
        session.captureBurst(requests, null, handler)
        plans.forEachIndexed { i, plan -> receive().use { consume(i, it, plan.third) } }
        return plans.map { it.third }
    }

    private suspend fun compensationBracket(evs: List<Double>, consume: (Int, Image, Double) -> Unit): List<Double> {
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0
        try {
            return evs.mapIndexed { i, ev ->
                val steps = PhotoHdrProbe.compensationSteps(chars, ev) ?: 0
                preview(steps)
                val from = meter.value.frame + 1
                withTimeoutOrNull(AE_SETTLE_MS) {
                    meter.first { m -> m.frame >= from && m.compensation == steps && m.aeState != CaptureResult.CONTROL_AE_STATE_SEARCHING }
                }
                val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                session.capture(still, null, handler)
                val relative = 2.0.pow(steps * step)
                receive().use { consume(i, it, relative) }
                relative
            }
        } finally {
            runCatching { preview(0) }
        }
    }

    private suspend fun receive(): Image = withTimeout(FRAME_TIMEOUT_MS) { images.receive() }

    fun close() {
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        device.close()
        reader.close()
        while (true) images.tryReceive().getOrNull()?.close() ?: break
        images.close()
    }

    private data class Metered(val frame: Long, val aeState: Int?, val exposureNs: Long?, val iso: Int?, val compensation: Int?)

    companion object {
        /** Longest bracket frame (1/8 s): longer ones blur hand-held and the remainder goes to ISO. */
        const val MAX_EXPOSURE_NS = 125_000_000L
        const val FRAME_TIMEOUT_MS = 5_000L
        const val AE_SETTLE_MS = 1_200L

        /** Biggest bracket frame: 12.5 MP keeps three frames on disk and one fused bitmap within modest phones. */
        const val MAX_FUSION_PIXELS = 12_500_000L
        private const val MAX_IMAGES = 2

        /** The YUV still size (largest within [MAX_FUSION_PIXELS], JPEG aspect first) and its preview size. */
        fun sizes(chars: CameraCharacteristics): PhotoSizes? {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val yuv = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().map { PixelSize(it.width, it.height) }.filter { it.area <= MAX_FUSION_PIXELS }
            val jpegAspect = map.getOutputSizes(ImageFormat.JPEG).orEmpty().maxByOrNull { it.width.toLong() * it.height }
                ?.let { it.width.toDouble() / it.height }
            val sameAspect = jpegAspect?.let { a -> yuv.filter { kotlin.math.abs(it.aspect - a) < PhotoSizes.ASPECT_TOLERANCE } }.orEmpty()
            val preview = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { PixelSize(it.width, it.height) }
            return PhotoSizes.choose(sameAspect.ifEmpty { yuv }, preview)
        }

        suspend fun open(
            manager: CameraManager,
            cameraId: String,
            previewTarget: Surface,
            still: PixelSize,
            handler: Handler,
            onLost: (String) -> Unit
        ): BracketCaptureStream {
            val chars = manager.getCameraCharacteristics(cameraId)
            val reader = ImageReader.newInstance(still.width, still.height, ImageFormat.YUV_420_888, MAX_IMAGES)
            var device: CameraDevice? = null
            var opened = false
            try {
                device = manager.openCameraDevice(cameraId, handler, onLost)
                val outputs = listOf(OutputConfiguration(previewTarget), OutputConfiguration(reader.surface))
                val session = device.createSession(outputs, Executor { handler.post(it) })
                val stream = BracketCaptureStream(device, session, reader, previewTarget, handler, chars)
                stream.preview()
                opened = true
                return stream
            } finally {
                if (!opened) {
                    device?.close()
                    reader.close()
                }
            }
        }
    }
}
