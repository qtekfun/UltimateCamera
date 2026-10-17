package com.qtekfun.ultimatecamera.core.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface

/**
 * How fast the depth outputs of a camera can run in a video session (D89): the minimum frame duration in nanoseconds of
 * each DEPTH16 size ([depth16], 0 = not reported) and of the stereo pair's shared YUV size ([stereo], the slower of
 * the two physical cameras). A depth output slower than the video's frame interval would stretch every request that
 * fills it, so the take would lose frames: such an output is never used for video.
 */
data class VideoDepthRates(val depth16: Map<PixelSize, Long> = emptyMap(), val stereo: StereoRate? = null)

/** The stereo pair's shared YUV [size] and the longer of its two minimum frame durations. */
data class StereoRate(val size: PixelSize, val minFrameNanos: Long)

/** Picks the depth outputs of a video session (D89); pure, so every phone class is unit-tested. */
object VideoDepthSelector {
    private const val NANOS_PER_SECOND = 1_000_000_000L

    /** Frame interval of [fps] in nanoseconds. */
    fun frameNanos(fps: Int): Long = NANOS_PER_SECOND / fps.coerceAtLeast(1)

    /** True when an output with [minFrameNanos] keeps up with [fps] (an unreported duration does not count as fast). */
    fun keepsUp(minFrameNanos: Long?, fps: Int): Boolean = minFrameNanos != null && minFrameNanos in 1..frameNanos(fps)

    /**
     * The outputs that give [source]'s depth to a [video]-sized take at [fps]: the DEPTH16 size fast enough with the
     * aspect closest to the video's, or the stereo pair's two YUV streams; null when the source has none fast enough
     * (or is the maker's extension, which has no video session).
     */
    fun outputs(source: DepthSource, support: DepthSupport, rates: VideoDepthRates, video: PixelSize, fps: Int): List<AuxOutput>? = when (source) {
        DepthSource.DEPTH_CAMERA -> DepthCaptureStream.closestAspect(support.depth16.filter { keepsUp(rates.depth16[it], fps) }, video)
            ?.let { listOf(AuxOutput(it, ImageFormat.DEPTH16, null)) }
        DepthSource.STEREO -> {
            val pair = support.stereo
            val rate = rates.stereo
            if (pair == null || rate == null || !keepsUp(rate.minFrameNanos, fps)) {
                null
            } else {
                listOf(pair.main, pair.second).map { AuxOutput(rate.size, ImageFormat.YUV_420_888, it) }
            }
        }
        DepthSource.VENDOR_BOKEH -> null
    }
}

/** Reads [VideoDepthRates] from Camera2 (D89). Call off the main thread; a failing query means "not fast enough". */
object VideoDepthProbe {
    fun read(manager: CameraManager, cameraId: String, support: DepthSupport, video: PixelSize): VideoDepthRates {
        val map = runCatching { manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }.getOrNull()
        val depth16 = support.depth16.associateWith { size ->
            map?.let { m -> runCatching { m.getOutputMinFrameDuration(ImageFormat.DEPTH16, android.util.Size(size.width, size.height)) }.getOrNull() } ?: 0L
        }
        return VideoDepthRates(depth16, support.stereo?.let { stereoRate(manager, it, video) })
    }

    private fun stereoRate(manager: CameraManager, pair: StereoPair, video: PixelSize): StereoRate? {
        val maps = listOf(pair.main, pair.second).map { id ->
            runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }.getOrNull() ?: return null
        }
        val sizes = maps.map { m -> m.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().map { PixelSize(it.width, it.height) }.toSet() }
        val size = DepthCaptureStream.sharedStereoSize(sizes[0], sizes[1], video) ?: return null
        val yuv = android.util.Size(size.width, size.height)
        val durations = maps.map { m -> runCatching { m.getOutputMinFrameDuration(ImageFormat.YUV_420_888, yuv) }.getOrDefault(0L) }
        return StereoRate(size, if (durations.any { it <= 0L }) 0L else durations.max())
    }
}

/**
 * The depth outputs of a video session (D89): one DEPTH16 reader, or one small YUV reader per physical camera of the
 * stereo pair, filled by one repeating request in [every] ([LiveDepthBurst]). Each frame (a DEPTH16 image, or a pair
 * of stereo images matched by timestamp) is copied out of the camera's buffers and handed to [consumer] on the camera
 * thread; the consumer must return fast and drop what it cannot handle. [framesDelivered] lets the caller notice a
 * HAL that configures the outputs but never fills them.
 */
class VideoDepthOutputs(outputs: List<AuxOutput>, private val stereo: StereoPair?, val every: Int, handler: Handler) {
    private val readers = outputs.map { ImageReader.newInstance(it.size.width, it.size.height, it.format, MAX_IMAGES) to it.physicalId }
    private val pairer = StereoFramePairer<LiveImage>(DepthCaptureStream.STEREO_PAIR_TOLERANCE_NANOS)

    @Volatile var consumer: ((DepthFrame) -> Unit)? = null

    /** Depth frames handed to [consumer] (or that would have been) since the session opened. */
    @Volatile var framesDelivered = 0
        private set

    init {
        require(every >= 1) { "depth cadence" }
        readers.forEachIndexed { index, (reader, _) ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                image.use { onImage(index, it) }
            }, handler)
        }
    }

    /** The surfaces the depth requests target. */
    val surfaces: List<Surface> get() = readers.map { it.first.surface }

    /** Output configurations for the session (physical streams carry their camera id). */
    fun configurations(): List<OutputConfiguration> =
        readers.map { (reader, physical) -> OutputConfiguration(reader.surface).apply { physical?.let(::setPhysicalCameraId) } }

    private fun onImage(index: Int, image: Image) {
        val plane = image.planes[0]
        val pair = stereo
        if (pair == null) {
            deliver(DepthFrame.Depth16(DepthCaptureStream.depth16Samples(plane.buffer), image.width, image.height, plane.rowStride / 2))
            return
        }
        val luma = DepthCaptureStream.packLuma(plane.buffer, plane.rowStride, plane.pixelStride, image.width, image.height)
        val (main, second) = pairer.offer(index, image.timestamp, LiveImage(luma, image.width, image.height)) ?: return
        val width = minOf(main.width, second.width)
        val height = minOf(main.height, second.height)
        deliver(DepthFrame.Stereo(main.crop(width, height), second.crop(width, height), width, height, pair))
    }

    private fun deliver(frame: DepthFrame) {
        framesDelivered++
        consumer?.invoke(frame)
    }

    fun close() {
        consumer = null
        readers.forEach { it.first.close() }
    }

    private companion object {
        const val MAX_IMAGES = 2
    }
}
