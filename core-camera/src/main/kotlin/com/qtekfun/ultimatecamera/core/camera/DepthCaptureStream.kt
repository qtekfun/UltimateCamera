package com.qtekfun.ultimatecamera.core.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** What a portrait shot captured next to the JPEG (D73), copied out of the camera's buffers. */
sealed interface DepthFrame {
    /** The camera's DEPTH16 image: [samples] rows start every [rowStride] samples. */
    class Depth16(val samples: ShortArray, val width: Int, val height: Int, val rowStride: Int) : DepthFrame

    /** Luma of the two physical cameras of a [StereoPair], [width] x [height] each, one byte per pixel. */
    class Stereo(val main: ByteArray, val second: ByteArray, val width: Int, val height: Int, val pair: StereoPair) : DepthFrame
}

/**
 * A portrait shot: the JPEG (not rotated: [DepthCaptureStream] asks for orientation 0) and its [depth], null when the
 * depth outputs delivered nothing for this request (the caller then saves a normal photo, never waits or fails).
 */
class PortraitShot(val jpeg: ByteArray, val depth: DepthFrame?)

/**
 * A portrait photo session (D73) for the sources that need our own blur: preview into the GPU stage, a full-size JPEG,
 * and either the camera's DEPTH16 output ([DepthSource.DEPTH_CAMERA]) or two small YUV streams of the physical cameras
 * of a [StereoPair] ([DepthSource.STEREO], physical streams of a logical multi-camera). One request fills every
 * output, so the depth matches the photo. The JPEG is taken with `JPEG_ORIENTATION` 0, so photo and depth stay in the
 * sensor's frame; the caller rotates by EXIF. One capture at a time.
 */
class DepthCaptureStream private constructor(
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
    private val readers: Readers,
    private val previewTarget: Surface,
    private val handler: Handler,
    private val stereo: StereoPair?
) {
    private class Readers(val jpeg: ImageReader, val aux: List<ImageReader>) {
        val all: List<ImageReader> get() = listOf(jpeg) + aux

        fun close() = all.forEach { it.close() }
    }

    private val channels = readers.all.associateWith { Channel<Image>(Channel.UNLIMITED) }

    init {
        channels.forEach { (reader, channel) ->
            reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { if (channel.trySend(it).isFailure) it.close() } }, handler)
        }
    }

    private fun startPreview() {
        val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewTarget)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }.build()
        session.setRepeatingRequest(preview, null, handler)
    }

    /**
     * Takes the JPEG and its depth in one request. Throws only when the JPEG itself does not arrive; depth outputs that
     * report a lost buffer, or that stay silent for [AUX_TIMEOUT_MS] after the JPEG, give a shot without depth.
     */
    suspend fun capture(quality: Int = StillCaptureStream.DEFAULT_QUALITY, withDepth: Boolean = true): PortraitShot {
        drain()
        val lost = MutableStateFlow(emptySet<Surface>())
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                lost.value = lost.value + target
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                lost.value = lost.value + readers.aux.map { it.surface }
            }
        }
        val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            (if (withDepth) readers.all else listOf(readers.jpeg)).forEach { addTarget(it.surface) }
            addTarget(previewTarget)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.JPEG_ORIENTATION, 0)
            set(CaptureRequest.JPEG_QUALITY, quality.toByte())
        }.build()
        session.capture(still, callback, handler)
        val jpeg = withTimeout(StillCaptureStream.IMAGE_TIMEOUT_MS) {
            channels.getValue(readers.jpeg).receive().use { image ->
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
        val images = ArrayList<Image>()
        try {
            if (withDepth) {
                withTimeoutOrNull(AUX_TIMEOUT_MS) {
                    for (reader in readers.aux) awaitFrame(reader, lost)?.let(images::add)
                }
            }
            return PortraitShot(jpeg, if (images.size == readers.aux.size) depthFrame(images) else null)
        } finally {
            images.forEach { it.close() }
        }
    }

    /** The next frame of [reader], or null as soon as the camera reports its buffer of this request lost. */
    private suspend fun awaitFrame(reader: ImageReader, lost: MutableStateFlow<Set<Surface>>): Image? = coroutineScope {
        val lostSignal = async { lost.first { reader.surface in it } }
        try {
            select {
                channels.getValue(reader).onReceive { it }
                lostSignal.onAwait { null }
            }
        } finally {
            lostSignal.cancel()
        }
    }

    private fun depthFrame(images: List<Image>): DepthFrame {
        val pair = stereo
        if (pair == null) {
            val image = images.single()
            val plane = image.planes[0]
            return DepthFrame.Depth16(depth16Samples(plane.buffer), image.width, image.height, plane.rowStride / 2)
        }
        val (main, second) = images
        val width = minOf(main.width, second.width)
        val height = minOf(main.height, second.height)
        return DepthFrame.Stereo(luma(main, width, height), luma(second, width, height), width, height, pair)
    }

    private fun luma(image: Image, width: Int, height: Int): ByteArray {
        val plane = image.planes[0]
        return packLuma(plane.buffer, plane.rowStride, plane.pixelStride, width, height)
    }

    private fun drain() = channels.values.forEach { c -> while (true) c.tryReceive().getOrNull()?.close() ?: break }

    fun close() {
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        device.close()
        readers.close()
        drain()
        channels.values.forEach { it.close() }
    }

    companion object {
        private const val MAX_IMAGES = 2

        /** How long the depth outputs may lag the JPEG of the same request before the shot goes without depth. */
        const val AUX_TIMEOUT_MS = 1_500L

        /** Widest YUV stream of the stereo pair: block matching runs on a reduced image anyway. */
        const val STEREO_MAX_WIDTH = 640

        /** The still and preview sizes of a portrait session on [chars] (the JPEG sizes of the camera itself). */
        fun sizes(chars: CameraCharacteristics): PhotoSizes? = StillCaptureStream.sizes(chars)

        /**
         * Opens [cameraId] for [source] ([DepthSource.DEPTH_CAMERA] or [DepthSource.STEREO] with [support]'s pair):
         * preview into [previewTarget], JPEG at [jpeg]. Throws [SessionRejectedException] when the HAL refuses the
         * outputs (many phones do not allow two physical streams with a JPEG).
         */
        @Suppress("LongParameterList")
        suspend fun open(
            manager: CameraManager,
            cameraId: String,
            source: DepthSource,
            support: DepthSupport,
            previewTarget: Surface,
            jpeg: PixelSize,
            handler: Handler,
            onLost: (String) -> Unit
        ): DepthCaptureStream {
            val pair = if (source == DepthSource.STEREO) support.stereo ?: throw SessionRejectedException("no stereo pair") else null
            val jpegReader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, MAX_IMAGES)
            val aux = ArrayList<Pair<ImageReader, String?>>()
            var device: CameraDevice? = null
            var opened = false
            try {
                if (pair == null) {
                    val depth = closestAspect(support.depth16, jpeg) ?: throw SessionRejectedException("no DEPTH16 output")
                    aux += ImageReader.newInstance(depth.width, depth.height, ImageFormat.DEPTH16, MAX_IMAGES) to null
                } else {
                    val size = stereoSize(manager, pair, jpeg) ?: throw SessionRejectedException("no YUV size shared by the stereo pair")
                    for (id in listOf(pair.main, pair.second)) {
                        aux +=
                            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, MAX_IMAGES) to id
                    }
                }
                device = manager.openCameraDevice(cameraId, handler, onLost)
                val outputs = listOf(OutputConfiguration(previewTarget), OutputConfiguration(jpegReader.surface)) +
                    aux.map { (reader, physical) -> OutputConfiguration(reader.surface).apply { physical?.let(::setPhysicalCameraId) } }
                if (!device.supports(outputs)) throw SessionRejectedException("the camera does not support these portrait outputs")
                val session = device.createSession(outputs, Executor { handler.post(it) })
                val stream = DepthCaptureStream(device, session, Readers(jpegReader, aux.map { it.first }), previewTarget, handler, pair)
                stream.startPreview()
                opened = true
                return stream
            } finally {
                if (!opened) {
                    device?.close()
                    jpegReader.close()
                    aux.forEach { it.first.close() }
                }
            }
        }

        /** DEPTH16 samples of an image buffer, read in the platform's byte order (camera buffers are native-endian). */
        fun depth16Samples(buffer: ByteBuffer): ShortArray {
            val shorts = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
            return ShortArray(shorts.remaining()).also { shorts.get(it) }
        }

        /** The top-left [width] x [height] of a Y plane with [rowStride] and [pixelStride] as packed bytes. */
        fun packLuma(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, width: Int, height: Int): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                if (pixelStride == 1) {
                    buffer.position(y * rowStride)
                    buffer.get(out, y * width, width)
                } else {
                    for (x in 0 until width) out[y * width + x] = buffer.get(y * rowStride + x * pixelStride)
                }
            }
            return out
        }

        /** A size both cameras list ([main], [second]), at most [STEREO_MAX_WIDTH] wide, with [jpeg]'s aspect if possible. */
        fun sharedStereoSize(main: Set<PixelSize>, second: Set<PixelSize>, jpeg: PixelSize): PixelSize? =
            closestAspect((main intersect second).filter { it.width <= STEREO_MAX_WIDTH }, jpeg)

        /**
         * Asks the camera whether it supports [outputs] together (`isSessionConfigurationSupported`) before opening the
         * session: a combination of physical streams the HAL cannot run is then never offered. A camera that cannot
         * answer (the query is optional for HALs) counts as supporting it; the capture then falls back on its own.
         */
        private fun CameraDevice.supports(outputs: List<OutputConfiguration>): Boolean = try {
            isSessionConfigurationSupported(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, Executor { }, NoopStateCallback)
            )
        } catch (_: UnsupportedOperationException) {
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: CameraAccessException) {
            true
        }

        private object NoopStateCallback : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = Unit

            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
        }

        /** The size in [sizes] with the aspect closest to [target], the largest among equals; null when empty. */
        fun closestAspect(sizes: List<PixelSize>, target: PixelSize): PixelSize? =
            sizes.minWithOrNull(compareBy<PixelSize> { abs(it.aspect - target.aspect) }.thenByDescending { it.area })

        /** A YUV size both physical cameras list, at most [STEREO_MAX_WIDTH] wide, with the JPEG's aspect if possible. */
        private fun stereoSize(manager: CameraManager, pair: StereoPair, jpeg: PixelSize): PixelSize? {
            fun yuv(id: String): Set<PixelSize> = runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().map { PixelSize(it.width, it.height) }.toSet()
            }.getOrDefault(emptySet())
            return sharedStereoSize(yuv(pair.main), yuv(pair.second), jpeg)
        }
    }
}
