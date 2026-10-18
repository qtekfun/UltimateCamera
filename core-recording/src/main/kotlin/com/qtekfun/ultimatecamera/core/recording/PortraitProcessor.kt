package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.qtekfun.ultimatecamera.core.camera.DepthFrame
import com.qtekfun.ultimatecamera.core.camera.PortraitShot
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import com.qtekfun.ultimatecamera.core.gpu.portrait.Argb
import com.qtekfun.ultimatecamera.core.gpu.portrait.ArgbBands
import com.qtekfun.ultimatecamera.core.gpu.portrait.BlurJob
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.gpu.portrait.CpuPortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.Depth16
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthImage
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthPng16
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthRefine
import com.qtekfun.ultimatecamera.core.gpu.portrait.DiscKernel
import com.qtekfun.ultimatecamera.core.gpu.portrait.GreyPng
import com.qtekfun.ultimatecamera.core.gpu.portrait.GuidedUpsample
import com.qtekfun.ultimatecamera.core.gpu.portrait.PlaneTurn
import com.qtekfun.ultimatecamera.core.gpu.portrait.PortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.StereoDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.reduced
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * How a portrait shot is rendered: [fNumber] (0 = no blur), the focus point in the sensor frame, the depth PNG, and
 * whether the unblurred photo and the depth with its real range are kept for editing after capture ([keepEdit], D88).
 */
data class PortraitLook(val fNumber: Float, val focusX: Float = 0.5f, val focusY: Float = 0.5f, val saveDepth: Boolean = false, val keepEdit: Boolean = true)

/**
 * Renders a portrait photo from a [PortraitShot] (D73): the depth (DEPTH16, or block-matched disparity of the stereo
 * pair) becomes inverse depth, is refined where it is weak ([DepthRefine], D88), upsampled edge-aware with the photo as
 * guide ([GuidedUpsample]), turned into a circle of confusion per pixel ([Bokeh]) around the depth at the focus point,
 * and the photo is blurred with [DepthBlur] at a reduced size and composited at full size in row bands (memory: the
 * photo bitmap plus small planes). The output stays in the sensor frame and carries the rotation as EXIF orientation.
 */
internal class PortraitProcessor(private val workDir: File, private val kernels: () -> PortraitKernels = { CpuPortraitKernels }) {
    /**
     * One rendered shot: the [jpeg] in [workDir], [timings] per stage, the [kernels] that did the heavy steps ("gpu",
     * or "cpu" as reference and fallback, D87), what the depth refinement did ([refine]) and the depth files still to
     * write ([side]), which wait until the photo is saved (D88, D92).
     */
    class Output(val jpeg: File, val timings: StageTimer, val kernels: String, val refine: String, val side: SideFiles)

    /** Renders [shot] with the kernels made for this call (on this thread, closed before returning). */
    fun render(shot: PortraitShot, look: PortraitLook, rotation: Int, quality: Int): Output = kernels().use { k -> render(shot, look, rotation, quality, k) }

    private fun render(shot: PortraitShot, look: PortraitLook, rotation: Int, quality: Int, k: PortraitKernels): Output {
        workDir.mkdirs()
        val timer = StageTimer()
        val frame = shot.depth ?: throw IOException("portrait shot without depth")
        val photo = BitmapFactory.decodeByteArray(shot.jpeg, 0, shot.jpeg.size, mutableArgb()) ?: throw IOException("portrait JPEG not decodable")
        timer.mark("decode")
        try {
            val depth = PortraitMaps.inverseDepth(frame, k)
            timer.mark("depth")
            val rendered = blurPhoto(photo, depth, look, k, timer)
            val jpeg = File(workDir, "portrait.jpg")
            jpeg.outputStream().use { if (!photo.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG encode failed") }
            PhotoExif.stamp(jpeg, LocalDateTime.now(), PortraitMaps.exifOrientation(rotation))
            timer.mark("encode")
            return Output(jpeg, timer, k.name, rendered.refine.describe(), SideFiles(rendered, depth, look, rotation))
        } finally {
            photo.recycle()
        }
    }

    /**
     * The depth files of a shot, made by [write] once the photo is saved (D88, D92): the depth PNG when the look asks
     * for it (`…_depth.png`: the guided depth at the reduced photo's size, upright, 8-bit grey, white = near) and the
     * 16-bit depth for editing ([DepthPng16]). Only the planes of the render are kept, never the photo.
     */
    inner class SideFiles internal constructor(
        private val rendered: Rendered,
        private val depth: Plane,
        private val look: PortraitLook,
        private val rotation: Int
    ) {
        /** What [write] made, in [workDir]; [timings] per file. */
        inner class Written(val depthPng: File?, val depthData: File?, val timings: StageTimer)

        fun write(): Written {
            workDir.mkdirs()
            val timer = StageTimer()
            val png = if (look.saveDepth) depthPng() else null
            timer.mark("depth png")
            val data = if (look.keepEdit) depthData() else null
            timer.mark("depth16 png")
            return Written(png, data, timer)
        }

        private fun depthData(): File {
            val file = File(workDir, "portrait_depth16.png")
            file.outputStream().buffered().use { DepthPng16.write(depth, DepthPng16.Meta(rotation, look.fNumber, look.focusX, look.focusY), it) }
            return file
        }

        private fun depthPng(): File {
            val upright = PlaneTurn.rotate(rendered.guide.upsample(rendered.luma, Runtime.getRuntime().availableProcessors()), rotation)
            val file = File(workDir, "portrait_depth.png")
            file.outputStream().buffered().use { GreyPng.write(DepthImage.grey(upright), upright.width, upright.height, it) }
            return file
        }
    }

    /** What [blurPhoto] used: the upsampler (its guide is [luma], the reduced photo) and the refinement. */
    class Rendered(val guide: GuidedUpsample, val luma: Plane, val refine: DepthRefine.Result)

    companion object {
        /** Width of the reduced copy the blur runs on (cost bound on modest phones). */
        const val REDUCED_WIDTH = 1024

        /** Decode options for a photo [blurPhoto] can work on in place. */
        fun mutableArgb(): BitmapFactory.Options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        /**
         * Blurs the mutable ARGB_8888 [photo] (sensor frame) in place from its raw inverse [depth] (any size, the
         * photo's aspect) for [look]: the depth is refined where it is weak, upsampled with the photo as guide, and the
         * reduced gather blur is composited at full size. The capture and the edit after capture (D88) both run this,
         * so an edit with the capture's look gives the capture's picture. Stages go to [timer].
         */
        fun blurPhoto(photo: Bitmap, depth: Plane, look: PortraitLook, k: PortraitKernels, timer: StageTimer = StageTimer()): Rendered {
            val scale = ceil(photo.width.toFloat() / REDUCED_WIDTH).toInt().coerceAtLeast(1)
            val small = reducedTile(photo, photo.width / scale, photo.height / scale)
            timer.mark("reduce")
            val guideLow = small.luma.reduced(depth.width, depth.height)
            val colourLow =
                with(small.tile) {
                    RgbTile(
                        depth.width,
                        depth.height,
                        r.reduced(depth.width, depth.height),
                        g.reduced(depth.width, depth.height),
                        b.reduced(depth.width, depth.height)
                    )
                }
            val refine = DepthRefine.refine(depth, colourLow, PortraitMaps.focus(depth, look), look.fNumber)
            val guide = GuidedUpsample(refine.depth, guideLow)
            val focus = PortraitMaps.focus(refine.depth, look)
            timer.mark("refine")
            // The reduced CoC, the gather and the full-size composite in one kernel call (D92: on the GPU nothing comes
            // back to the CPU but the finished rows); f-number 0 means no blur at all.
            if (look.fNumber > 0f) k.blur(BlurJob(small.tile, small.argb, small.luma, guide, focus, look.fNumber), BitmapBands(photo))
            timer.mark("blur")
            return Rendered(guide, small.luma, refine)
        }

        /** The photo's rows for the blur (the CPU composite locks around each call). */
        private class BitmapBands(private val photo: Bitmap) : ArgbBands {
            override val width: Int get() = photo.width
            override val height: Int get() = photo.height

            override fun read(top: Int, rows: Int, into: IntArray) = photo.getPixels(into, 0, width, 0, top, width, rows)

            override fun write(top: Int, rows: Int, from: IntArray) = photo.setPixels(from, 0, width, 0, top, width, rows)
        }

        private class Reduced(val tile: RgbTile, val argb: IntArray, val luma: Plane)

        private fun reducedTile(photo: Bitmap, width: Int, height: Int): Reduced {
            val scaled = Bitmap.createScaledBitmap(photo, width, height, true)
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            if (scaled !== photo) scaled.recycle()
            val tile = RgbTile(width, height)
            val luma = Plane(width, height)
            for (i in pixels.indices) {
                val c = pixels[i]
                tile.r.data[i] = Argb.r(c)
                tile.g.data[i] = Argb.g(c)
                tile.b.data[i] = Argb.b(c)
                luma.data[i] = Argb.luma(c)
            }
            return Reduced(tile, pixels, luma)
        }
    }
}
