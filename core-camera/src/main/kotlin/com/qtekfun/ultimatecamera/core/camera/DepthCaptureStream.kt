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

    /** True while [capture] waits for its frames: depth images then go to it, never to the live consumer. */
    @Volatile private var capturing = false

    /** Live depth (D83): one request in [liveEvery] of the preview also fills the depth outputs. Camera thread. */
    private var liveConsumer: ((DepthFrame) -> Unit)? = null
    private var liveEvery = 0
    private val livePairer = StereoFramePairer<LiveImage>(STEREO_PAIR_TOLERANCE_NANOS)

    init {
        channels.forEach { (reader, channel) ->
            val aux = readers.aux.indexOf(reader)
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                if (aux < 0 || capturing) {
                    if (channel.trySend(image).isFailure) image.close()
                } else {
                    image.use { live(aux, it) }
                }
            }, handler)
        }
    }

    private fun previewRequest(withDepth: Boolean): CaptureRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(previewTarget)
        if (withDepth) readers.aux.forEach { addTarget(it.surface) }
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    }.build()

    /** The repeating preview: plain, or with live depth every [liveEvery]-th request ([LiveDepthBurst]). */
    private fun startPreview(live: Boolean = liveConsumer != null) {
        val pattern = if (live) LiveDepthBurst.pattern(liveEvery) else listOf(false)
        if (pattern.size == 1 && !pattern[0]) {
            session.setRepeatingRequest(previewRequest(withDepth = false), null, handler)
        } else {
            val plain = previewRequest(withDepth = false)
            val depth = previewRequest(withDepth = true)
            session.setRepeatingBurst(pattern.map { if (it) depth else plain }, null, handler)
        }
    }

    /**
     * Starts (or with null [consumer] stops) live depth for the portrait preview (D83): the depth outputs are filled by
     * one preview request in [every] and each frame (a DEPTH16 image, or a matched pair of stereo images) is handed to
     * [consumer] on the camera thread, copied out of the camera's buffers. The consumer must return fast and drop what
     * it cannot handle. Any thread.
     */
    fun setLiveDepth(every: Int, consumer: ((DepthFrame) -> Unit)?) {
        handler.post {
            liveConsumer = consumer
            liveEvery = every.coerceAtLeast(1)
            livePairer.clear()
            if (!capturing) runCatching { startPreview() }
        }
    }

    /** One live depth image of aux output [index]; camera thread. */
    private fun live(index: Int, image: Image) {
        val consumer = liveConsumer ?: return
        val pair = stereo
        if (pair == null) {
            val plane = image.planes[0]
            consumer(DepthFrame.Depth16(depth16Samples(plane.buffer), image.width, image.height, plane.rowStride / 2))
            return
        }
        val plane = image.planes[0]
        val luma = packLuma(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height)
        val matched = livePairer.offer(index, image.timestamp, LiveImage(luma, image.width, image.height)) ?: return
        val (main, second) = matched
        val width = minOf(main.width, second.width)
        val height = minOf(main.height, second.height)
        consumer(DepthFrame.Stereo(main.crop(width, height), second.crop(width, height), width, height, pair))
    }

    /**
     * Takes the JPEG and its depth in one request. Throws only when the JPEG itself does not arrive; depth outputs that
     * report a lost buffer, or that stay silent for [AUX_TIMEOUT_MS] after the JPEG, give a shot without depth.
     */
    suspend fun capture(quality: Int = StillCaptureStream.DEFAULT_QUALITY, withDepth: Boolean = true): PortraitShot {
        val live = liveConsumer != null
        capturing = true
        try {
            // Live depth off while the shot is taken: the still is then the last request that fills the depth outputs.
            if (live) startPreview(live = false)
            drain()
            return captureOnce(quality, withDepth, live)
        } finally {
            capturing = false
            if (live) handler.post { if (!capturing) runCatching { startPreview() } }
        }
    }

    private suspend fun captureOnce(quality: Int, withDepth: Boolean, liveWasOn: Boolean): PortraitShot {
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
        var stillNanos = 0L
        val jpeg = withTimeout(StillCaptureStream.IMAGE_TIMEOUT_MS) {
            channels.getValue(readers.jpeg).receive().use { image ->
                stillNanos = image.timestamp
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
        val images = ArrayList<Image>()
        try {
            if (withDepth) {
                withTimeoutOrNull(AUX_TIMEOUT_MS) {
                    for (reader in readers.aux) stillFrame(reader, lost, stillNanos, liveWasOn)?.let(images::add)
                }
            }
            return PortraitShot(jpeg, if (images.size == readers.aux.size) depthFrame(images) else null)
        } finally {
            images.forEach { it.close() }
        }
    }

    /**
     * The depth image of the still on [reader]: without live depth the first one. With live depth, preview frames
     * already in flight may come first, so images are taken until one carries the still's timestamp or, failing that
     * (physical cameras keep their own clocks), the last one before the outputs go quiet for [AUX_SETTLE_MS]: live
     * depth is off during the shot, so the still is the last request that fills them. Null when the buffer is lost.
     */
    private suspend fun stillFrame(reader: ImageReader, lost: MutableStateFlow<Set<Surface>>, stillNanos: Long, liveWasOn: Boolean): Image? {
        var candidate: Image? = null
        try {
            var waiting = true
            while (waiting) {
                // A lost buffer before any image: no depth. After one: the outputs went quiet, keep the last image.
                val next = if (candidate == null) awaitFrame(reader, lost) ?: return null else withTimeoutOrNull(AUX_SETTLE_MS) { awaitFrame(reader, lost) }
                if (next == null) {
                    waiting = false
                } else {
                    candidate?.close()
                    candidate = next
                    waiting = !AuxPick.done(next.timestamp, stillNanos, liveWasOn)
                }
            }
            return candidate.also { candidate = null }
        } finally {
            candidate?.close()
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

        /** With live depth on: quiet time after the last depth image before it is taken as the still's. */
        const val AUX_SETTLE_MS = 150L

        /** Two stereo images this close in time come from the same preview request (physical clocks may differ). */
        const val STEREO_PAIR_TOLERANCE_NANOS = 20_000_000L

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
            val plan = auxOutputs(pair, support, jpeg) { stereoSize(manager, it, jpeg) }
            val jpegReader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, MAX_IMAGES)
            val aux = ArrayList<Pair<ImageReader, String?>>()
            var device: CameraDevice? = null
            var opened = false
            try {
                plan.forEach { o -> aux += ImageReader.newInstance(o.size.width, o.size.height, o.format, MAX_IMAGES) to o.physicalId }
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

        /**
         * The depth outputs next to the JPEG: one DEPTH16 stream with the aspect closest to [jpeg] without a stereo
         * [pair], else a YUV stream per physical camera of the pair at the size [stereoSize] finds. Throws
         * [SessionRejectedException] when the camera offers no such output.
         */
        fun auxOutputs(pair: StereoPair?, support: DepthSupport, jpeg: PixelSize, stereoSize: (StereoPair) -> PixelSize?): List<AuxOutput> {
            if (pair == null) {
                val depth = closestAspect(support.depth16, jpeg) ?: throw SessionRejectedException("no DEPTH16 output")
                return listOf(AuxOutput(depth, ImageFormat.DEPTH16, null))
            }
            val size = stereoSize(pair) ?: throw SessionRejectedException("no YUV size shared by the stereo pair")
            return listOf(pair.main, pair.second).map { AuxOutput(size, ImageFormat.YUV_420_888, it) }
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

/** One live stereo image's luma, [width] x [height] packed. */
internal class LiveImage(val luma: ByteArray, val width: Int, val height: Int) {
    /** The top-left [w] x [h] of the image (both stereo images cut to their common size). */
    fun crop(w: Int, h: Int): ByteArray {
        if (w == width && h == height) return luma
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(luma, y * width, out, y * w, w)
        return out
    }
}

/** Which repeating preview requests also fill the depth outputs (D83): the last of every [LiveDepthBurst.pattern]. */
object LiveDepthBurst {
    /** One true in [every] requests (≤ 1: every request), as the repeating burst's pattern. */
    fun pattern(every: Int): List<Boolean> = List(every.coerceAtLeast(1)) { it == every.coerceAtLeast(1) - 1 }
}

/** When the wait for the still's depth image is over (D83). */
object AuxPick {
    /** The same request on the logical camera carries the same sensor timestamp; allow a little for rounding. */
    const val SAME_REQUEST_NANOS = 1_000_000L

    /**
     * True when the image at [imageNanos] ends the wait: always without live depth (only the still fills the outputs),
     * and with it when the image carries the still's timestamp ([stillNanos]).
     */
    fun done(imageNanos: Long, stillNanos: Long, liveWasOn: Boolean): Boolean = !liveWasOn || abs(imageNanos - stillNanos) <= SAME_REQUEST_NANOS
}

/**
 * Pairs the live images of the two stereo outputs (D83): the newest image of each output is kept, and a pair is
 * returned once both are within [toleranceNanos] of each other (the same request); an older unmatched image is
 * replaced by the next one of its output. Camera thread only.
 */
class StereoFramePairer<T>(private val toleranceNanos: Long) {
    private val stamps = LongArray(2)
    private val items = arrayOfNulls<Any>(2)

    /** Offers [item] of output [index] (0 or 1) taken at [nanos]; the matched (first, second) pair, or null. */
    @Suppress("UNCHECKED_CAST")
    fun offer(index: Int, nanos: Long, item: T): Pair<T, T>? {
        require(index == 0 || index == 1) { "stereo output $index" }
        items[index] = item
        stamps[index] = nanos
        val other = 1 - index
        if (items[other] == null || abs(stamps[other] - nanos) > toleranceNanos) return null
        val pair = (items[0] as T) to (items[1] as T)
        clear()
        return pair
    }

    fun clear() {
        items[0] = null
        items[1] = null
    }
}

/** One depth output of a portrait session: [format] at [size], on the physical camera [physicalId] (null = the logical one). */
data class AuxOutput(val size: PixelSize, val format: Int, val physicalId: String?)
