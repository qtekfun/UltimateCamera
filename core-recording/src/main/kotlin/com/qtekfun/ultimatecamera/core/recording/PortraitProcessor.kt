package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.qtekfun.ultimatecamera.core.camera.DepthFrame
import com.qtekfun.ultimatecamera.core.camera.PortraitShot
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import com.qtekfun.ultimatecamera.core.gpu.portrait.Argb
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.gpu.portrait.CpuPortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.Depth16
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthBlur
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthPng16
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthRefine
import com.qtekfun.ultimatecamera.core.gpu.portrait.DiscKernel
import com.qtekfun.ultimatecamera.core.gpu.portrait.GuidedUpsample
import com.qtekfun.ultimatecamera.core.gpu.portrait.PortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.StereoDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.reduced
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
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
     * Files of one rendered shot in [workDir]: the JPEG and, when asked for, the depth PNG and the 16-bit depth for
     * editing ([depthData], [DepthPng16]); [timings] per stage, the [kernels] that did the heavy steps ("gpu", or "cpu"
     * as reference and fallback, D87) and what the depth refinement did ([refine]).
     */
    class Output(val jpeg: File, val depthPng: File?, val depthData: File?, val timings: StageTimer, val kernels: String, val refine: String)

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
            val png = if (look.saveDepth) depthPng(rendered.guide.upsample(rendered.luma), rotation) else null
            val data = if (look.keepEdit) depthData(depth, look, rotation) else null
            timer.mark("png")
            return Output(jpeg, png, data, timer, k.name, rendered.refine.describe())
        } finally {
            photo.recycle()
        }
    }

    private fun depthData(depth: Plane, look: PortraitLook, rotation: Int): File {
        val file = File(workDir, "portrait_depth16.png")
        file.outputStream().buffered().use { DepthPng16.write(depth, DepthPng16.Meta(rotation, look.fNumber, look.focusX, look.focusY), it) }
        return file
    }

    /** What [blurPhoto] used: the upsampler (its guide is [luma], the reduced photo) and the refinement. */
    class Rendered(val guide: GuidedUpsample, val luma: Plane, val refine: DepthRefine.Result)

    private fun depthPng(depth: Plane, rotation: Int): File {
        val pixels = PortraitMaps.greyArgb(depth)
        val bitmap = Bitmap.createBitmap(pixels, depth.width, depth.height, Bitmap.Config.ARGB_8888)
        val upright = if (rotation % FULL_TURN == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, false)
        }
        val file = File(workDir, "portrait_depth.png")
        file.outputStream().use { upright.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        if (upright !== bitmap) upright.recycle()
        bitmap.recycle()
        return file
    }

    companion object {
        /** Width of the reduced copy the blur runs on (cost bound on modest phones). */
        const val REDUCED_WIDTH = 1024
        private const val BAND_ROWS = 128
        private const val FULL_TURN = 360
        private const val PNG_QUALITY = 100

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
            val cocLow = Plane(small.tile.width, small.tile.height)
            for (y in 0 until cocLow.height) {
                for (x in 0 until cocLow.width) {
                    val inverse = guide.at((x + 0.5f) / cocLow.width, (y + 0.5f) / cocLow.height, small.luma[x, y])
                    cocLow[x, y] = Bokeh.cocPx(inverse, focus, look.fNumber, cocLow.width)
                }
            }
            timer.mark("coc")
            if (look.fNumber > 0f) blur(photo, small.tile, cocLow, guide, focus, look.fNumber, k)
            timer.mark("blur")
            return Rendered(guide, small.luma, refine)
        }

        private class Reduced(val tile: RgbTile, val luma: Plane)

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
            return Reduced(tile, luma)
        }

        /** Blurs [photo] in place: the reduced gather blur, then the full-size composite in row bands. */
        @Suppress("LongParameterList")
        private fun blur(photo: Bitmap, small: RgbTile, cocLow: Plane, guide: GuidedUpsample, focus: Float, fNumber: Float, k: PortraitKernels) {
            val blurred = k.gather(small, cocLow, DiscKernel.CAPTURE, DepthBlur.MAX_RADIUS)
            val edges = PortraitMaps.edgeMask(cocLow)
            val width = photo.width
            val height = photo.height
            val scale = width.toFloat() / cocLow.width
            val band = IntArray(width * BAND_ROWS)
            var top = 0
            while (top < height) {
                val rows = minOf(BAND_ROWS, height - top)
                val pixels = if (rows == BAND_ROWS) band else IntArray(width * rows)
                photo.getPixels(pixels, 0, width, 0, top, width, rows)
                DepthBlur.composite(pixels, top, width, height, blurred) { nx, ny, luma ->
                    val lx = (nx * cocLow.width).toInt().coerceIn(0, cocLow.width - 1)
                    val ly = (ny * cocLow.height).toInt().coerceIn(0, cocLow.height - 1)
                    // Edge-aware depth only where the blur changes (the subject's outline); bilinear elsewhere is enough.
                    if (edges[ly * cocLow.width + lx]) {
                        Bokeh.cocPx(guide.at(nx, ny, luma), focus, fNumber, width)
                    } else {
                        DepthBlur.bilinear(cocLow, nx, ny) * scale
                    }
                }
                photo.setPixels(pixels, 0, width, 0, top, width, rows)
                top += rows
            }
        }
    }
}
