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
        reader.setOnImageAvailableListener({ r -> drain(r) }, handler)
    }

    /**
     * Hands every ready frame to the consumer queue. When all [MAX_IMAGES] buffers are still held by frames being
     * written to disk, the rest stay in the reader and are picked up as soon as [release] frees one (a burst of three
     * crashed the photo thread with maxImages 2 on the Pixel 8).
     */
    private fun drain(r: ImageReader) {
        FrameDrain.drain({ r.acquireNextImage() }) { image -> if (images.trySend(image).isFailure) image.close() }
    }

    /** Closes a consumed frame and picks up any frame that was waiting for a free buffer. */
    private fun release(image: Image) {
        image.close()
        handler.post { drain(reader) }
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
        val plans = manualPlan(evs, t0, iso0, tRange?.let { it.lower..it.upper }, isoRange?.let { it.lower..it.upper })
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
        plans.forEachIndexed { i, plan ->
            val image = receive()
            try {
                consume(i, image, plan.third)
            } finally {
                release(image)
            }
        }
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
                val relative = compensationExposure(steps, step)
                val image = receive()
                try {
                    consume(i, image, relative)
                } finally {
                    release(image)
                }
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

        /** A whole −2/0/+2 bracket plus one, so the HAL never waits for the disk writer. */
        private const val MAX_IMAGES = 4

        /** The YUV still size (largest within [MAX_FUSION_PIXELS], JPEG aspect first) and its preview size. */
        fun sizes(chars: CameraCharacteristics): PhotoSizes? {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            fun read(sizes: Array<android.util.Size>?) = sizes.orEmpty().map { PixelSize(it.width, it.height) }
            return sizes(
                read(map.getOutputSizes(ImageFormat.YUV_420_888)),
                read(map.getOutputSizes(ImageFormat.JPEG)),
                read(map.getOutputSizes(SurfaceTexture::class.java))
            )
        }

        /** [sizes] on plain lists: YUV sizes within [MAX_FUSION_PIXELS], those with the largest JPEG's aspect first. */
        fun sizes(yuv: List<PixelSize>, jpeg: List<PixelSize>, preview: List<PixelSize>): PhotoSizes? {
            val fitting = yuv.filter { it.area <= MAX_FUSION_PIXELS }
            val jpegAspect = jpeg.maxByOrNull { it.area }?.aspect
            val sameAspect = jpegAspect?.let { a -> fitting.filter { kotlin.math.abs(it.aspect - a) < PhotoSizes.ASPECT_TOLERANCE } }.orEmpty()
            return PhotoSizes.choose(sameAspect.ifEmpty { fitting }, preview)
        }

        /** Exposure relative to the metered one after [steps] compensation steps of [stepEv] EV each. */
        fun compensationExposure(steps: Int, stepEv: Double): Double = 2.0.pow(steps * stepEv)

        /**
         * Exposure time, ISO and real exposure relative to the metered one ([t0] ns at [iso0]) for each EV of a manual
         * bracket: the time is clamped to [timeRange] and [MAX_EXPOSURE_NS] (hand-held), the rest goes to ISO within
         * [isoRange]; a camera without ranges keeps the metered ISO.
         */
        fun manualPlan(evs: List<Double>, t0: Long, iso0: Int, timeRange: LongRange?, isoRange: IntRange?): List<Triple<Long, Int, Double>> = evs.map { ev ->
            val wanted = t0 * 2.0.pow(ev)
            val t = wanted.toLong().coerceIn(timeRange?.first ?: 1L, minOf(timeRange?.last ?: MAX_EXPOSURE_NS, MAX_EXPOSURE_NS))
            val iso = (iso0 * wanted / t).toInt().coerceIn(isoRange?.first ?: iso0, isoRange?.last ?: iso0)
            Triple(t, iso, (t.toDouble() * iso) / (t0.toDouble() * iso0))
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
