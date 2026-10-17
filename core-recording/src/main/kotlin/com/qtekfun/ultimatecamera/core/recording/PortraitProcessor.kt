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
import com.qtekfun.ultimatecamera.core.gpu.portrait.Depth16
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthBlur
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthImage
import com.qtekfun.ultimatecamera.core.gpu.portrait.GuidedUpsample
import com.qtekfun.ultimatecamera.core.gpu.portrait.StereoDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.reduced
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import kotlin.math.ceil

/** How a portrait shot is rendered: [fNumber] (0 = no blur), the focus point in the sensor frame, the depth PNG. */
data class PortraitLook(val fNumber: Float, val focusX: Float = 0.5f, val focusY: Float = 0.5f, val saveDepth: Boolean = false)

/**
 * Renders a portrait photo from a [PortraitShot] (D73): the depth (DEPTH16, or block-matched disparity of the stereo
 * pair) becomes inverse depth, is upsampled edge-aware with the photo as guide ([GuidedUpsample]), turned into a
 * circle of confusion per pixel ([Bokeh]) around the depth at the focus point, and the photo is blurred with
 * [DepthBlur] at a reduced size and composited at full size in row bands (memory: the photo bitmap plus small planes).
 * The output stays in the sensor frame and carries the rotation as EXIF orientation.
 */
internal class PortraitProcessor(private val workDir: File) {
    /** Files of one rendered shot in [workDir]: the JPEG and, when asked for, the depth PNG. */
    class Output(val jpeg: File, val depthPng: File?)

    fun render(shot: PortraitShot, look: PortraitLook, rotation: Int, quality: Int): Output {
        workDir.mkdirs()
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val photo = BitmapFactory.decodeByteArray(shot.jpeg, 0, shot.jpeg.size, options) ?: throw IOException("portrait JPEG not decodable")
        try {
            val scale = ceil(photo.width.toFloat() / REDUCED_WIDTH).toInt().coerceAtLeast(1)
            val small = reducedTile(photo, photo.width / scale, photo.height / scale)
            val depth = inverseDepth(shot.depth)
            val guide = GuidedUpsample(depth, small.luma.reduced(depth.width, depth.height))
            val focus = focus(depth, look)
            val cocLow = Plane(small.tile.width, small.tile.height)
            for (y in 0 until cocLow.height) {
                for (x in 0 until cocLow.width) {
                    val inverse = guide.at((x + HALF) / cocLow.width, (y + HALF) / cocLow.height, small.luma[x, y])
                    cocLow[x, y] = Bokeh.cocPx(inverse, focus, look.fNumber, cocLow.width)
                }
            }
            if (look.fNumber > 0f) blur(photo, small.tile, cocLow, guide, focus, look.fNumber)
            val jpeg = File(workDir, "portrait.jpg")
            jpeg.outputStream().use { if (!photo.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG encode failed") }
            PhotoExif.stamp(jpeg, LocalDateTime.now(), exifOrientation(rotation))
            val png = if (look.saveDepth) depthPng(guide.upsample(small.luma), rotation) else null
            return Output(jpeg, png)
        } finally {
            photo.recycle()
        }
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
    private fun blur(photo: Bitmap, small: RgbTile, cocLow: Plane, guide: GuidedUpsample, focus: Float, fNumber: Float) {
        val blurred = DepthBlur.gather(small, cocLow)
        val edges = edgeMask(cocLow)
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

    /** True where the reduced CoC changes by more than a pixel within its 3 x 3 neighbourhood. */
    private fun edgeMask(coc: Plane): BooleanArray {
        val mask = BooleanArray(coc.width * coc.height)
        for (y in 0 until coc.height) {
            for (x in 0 until coc.width) mask[y * coc.width + x] = spread(coc, x, y) > 1f
        }
        return mask
    }

    /** Largest minus smallest value of the 3 x 3 neighbourhood of ([x], [y]), clamped at the border. */
    private fun spread(coc: Plane, x: Int, y: Int): Float {
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val v = coc[(x + dx).coerceIn(0, coc.width - 1), (y + dy).coerceIn(0, coc.height - 1)]
                lo = minOf(lo, v)
                hi = maxOf(hi, v)
            }
        }
        return hi - lo
    }

    private fun focus(depth: Plane, look: PortraitLook): Float {
        val near = Bokeh.focusInverseDepth(depth, look.focusX, look.focusY)
        return if (near > Bokeh.INVALID) near else Bokeh.focusInverseDepth(depth, look.focusX, look.focusY, radius = depth.width / WIDE_FOCUS_DIVISOR)
    }

    private fun inverseDepth(frame: DepthFrame): Plane = when (frame) {
        is DepthFrame.Depth16 -> Depth16.inverseDepth(frame.samples, frame.width, frame.height, frame.rowStride)
        is DepthFrame.Stereo -> stereoDepth(frame)
    }

    /** Main luma and the second camera's centre crop (its view matched to the main's), both reduced, block-matched. */
    private fun stereoDepth(frame: DepthFrame.Stereo): Plane {
        val w = (frame.width / STEREO_REDUCTION).coerceAtLeast(1)
        val h = (frame.height / STEREO_REDUCTION).coerceAtLeast(1)
        val main = Plane(frame.width, frame.height) { i -> (frame.main[i].toInt() and 0xFF) / MAX_BYTE }.reduced(w, h)
        val crop = frame.pair.secondCrop
        val second = Plane(w, h)
        for (y in 0 until h) {
            val sy = ((HALF - crop / 2f + crop * (y + HALF) / h) * frame.height).toInt().coerceIn(0, frame.height - 1)
            for (x in 0 until w) {
                val sx = ((HALF - crop / 2f + crop * (x + HALF) / w) * frame.width).toInt().coerceIn(0, frame.width - 1)
                second[x, y] = (frame.second[sy * frame.width + sx].toInt() and 0xFF) / MAX_BYTE
            }
        }
        val maxDisparity = (w * MAX_DISPARITY_FRACTION).toInt().coerceAtLeast(2)
        return StereoDepth.inverseDepth(StereoDepth.disparity(main, second, maxDisparity), maxDisparity)
    }

    private fun depthPng(depth: Plane, rotation: Int): File {
        val grey = DepthImage.grey(depth)
        val pixels = IntArray(grey.size) { i ->
            val v = grey[i].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
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

    private fun Plane(width: Int, height: Int, value: (Int) -> Float): Plane = Plane(width, height, FloatArray(width * height, value))

    companion object {
        /** Width of the reduced copy the blur runs on (cost bound on modest phones). */
        const val REDUCED_WIDTH = 1024
        private const val BAND_ROWS = 128
        private const val HALF = 0.5f
        private const val MAX_BYTE = 255f
        private const val STEREO_REDUCTION = 2
        private const val MAX_DISPARITY_FRACTION = 0.05f
        private const val WIDE_FOCUS_DIVISOR = 8
        private const val FULL_TURN = 360
        private const val PNG_QUALITY = 100

        /** EXIF orientation for a clockwise turn of [degrees] (0, 90, 180, 270). */
        fun exifOrientation(degrees: Int): Int = when (((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }
}
