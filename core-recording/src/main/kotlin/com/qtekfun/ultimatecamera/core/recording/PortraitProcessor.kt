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
    /** Files of one rendered shot in [workDir]: the JPEG and, when asked for, the depth PNG; [timings] per stage. */
    class Output(val jpeg: File, val depthPng: File?, val timings: StageTimer)

    fun render(shot: PortraitShot, look: PortraitLook, rotation: Int, quality: Int): Output {
        workDir.mkdirs()
        val timer = StageTimer()
        val frame = shot.depth ?: throw IOException("portrait shot without depth")
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val photo = BitmapFactory.decodeByteArray(shot.jpeg, 0, shot.jpeg.size, options) ?: throw IOException("portrait JPEG not decodable")
        timer.mark("decode")
        try {
            val scale = ceil(photo.width.toFloat() / REDUCED_WIDTH).toInt().coerceAtLeast(1)
            val small = reducedTile(photo, photo.width / scale, photo.height / scale)
            timer.mark("reduce")
            val depth = PortraitMaps.inverseDepth(frame)
            timer.mark("depth")
            val guide = GuidedUpsample(depth, small.luma.reduced(depth.width, depth.height))
            val focus = PortraitMaps.focus(depth, look)
            val cocLow = Plane(small.tile.width, small.tile.height)
            for (y in 0 until cocLow.height) {
                for (x in 0 until cocLow.width) {
                    val inverse = guide.at((x + 0.5f) / cocLow.width, (y + 0.5f) / cocLow.height, small.luma[x, y])
                    cocLow[x, y] = Bokeh.cocPx(inverse, focus, look.fNumber, cocLow.width)
                }
            }
            timer.mark("coc")
            if (look.fNumber > 0f) blur(photo, small.tile, cocLow, guide, focus, look.fNumber)
            timer.mark("blur")
            val jpeg = File(workDir, "portrait.jpg")
            jpeg.outputStream().use { if (!photo.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG encode failed") }
            PhotoExif.stamp(jpeg, LocalDateTime.now(), PortraitMaps.exifOrientation(rotation))
            timer.mark("encode")
            val png = if (look.saveDepth) depthPng(guide.upsample(small.luma), rotation) else null
            timer.mark("png")
            return Output(jpeg, png, timer)
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
    }
}
