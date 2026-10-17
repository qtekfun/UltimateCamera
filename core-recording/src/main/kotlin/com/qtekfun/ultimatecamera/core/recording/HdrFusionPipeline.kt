package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Gainmap
import android.media.Image
import android.os.Build
import com.qtekfun.ultimatecamera.core.gpu.hdr.ExposureAlignment
import com.qtekfun.ultimatecamera.core.gpu.hdr.ExposureFusion
import com.qtekfun.ultimatecamera.core.gpu.hdr.FusionTile
import com.qtekfun.ultimatecamera.core.gpu.hdr.FusionTiling
import com.qtekfun.ultimatecamera.core.gpu.hdr.GainMapMath
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Our own HDR photo (D64): the bracket frames are written to disk as I420 as they arrive (the camera buffers are let go
 * at once), then mapped back read-only and fused tile by tile ([FusionTiling], [ExposureFusion]) into one upright
 * bitmap that is JPEG-encoded. On Android 14+ the bitmap carries a gain map ([GainMapMath]) so the JPEG is Ultra HDR.
 * Memory: the output bitmap plus a few tiles; the frames live in the page cache, not on the heap.
 */
class HdrFusionPipeline(private val workDir: File) {
    /** Writes [image] (YUV_420_888) as I420 to frame file [index]. */
    fun writeFrame(index: Int, image: Image): File {
        workDir.mkdirs()
        val file = File(workDir, "frame$index.i420")
        val w = image.width
        val h = image.height
        FileOutputStream(file).use { out ->
            val row = ByteArray(w)
            val y = image.planes[0]
            for (r in 0 until h) {
                readRow(y.buffer, r * y.rowStride, y.pixelStride, w, row)
                out.write(row, 0, w)
            }
            for (p in 1..2) {
                val plane = image.planes[p]
                for (r in 0 until h / 2) {
                    readRow(plane.buffer, r * plane.rowStride, plane.pixelStride, w / 2, row)
                    out.write(row, 0, w / 2)
                }
            }
            out.fd.sync()
        }
        return file
    }

    private fun readRow(buffer: java.nio.ByteBuffer, offset: Int, pixelStride: Int, count: Int, into: ByteArray) {
        if (pixelStride == 1) {
            buffer.position(offset)
            buffer.get(into, 0, count)
        } else {
            for (i in 0 until count) into[i] = buffer.get(offset + i * pixelStride)
        }
    }

    /**
     * Fuses [frames] ([width] x [height] I420, [relativeExposures] each; [reference] is the 0 EV one), turned by
     * [rotation] degrees clockwise, into a JPEG at [output]. With [gainMap] (Android 14+) the JPEG carries a gain map.
     */
    @Suppress("LongParameterList")
    fun fuse(
        frames: List<File>,
        relativeExposures: List<Double>,
        reference: Int,
        width: Int,
        height: Int,
        rotation: Int,
        gainMap: Boolean,
        quality: Int,
        output: File
    ) {
        val maps = frames.map { f -> RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) } }
        val sources = maps.map { I420(it, width, height) }
        val shifts = align(sources, reference)
        val turn = Turn(rotation, width, height)
        val bitmap = Bitmap.createBitmap(turn.width, turn.height, Bitmap.Config.ARGB_8888)
        val gm = if (gainMap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Bitmap.createBitmap((turn.width + GAIN_STEP - 1) / GAIN_STEP, (turn.height + GAIN_STEP - 1) / GAIN_STEP, Bitmap.Config.ALPHA_8)
        } else {
            null
        }
        val exposures = relativeExposures.toDoubleArray()
        try {
            for (tile in FusionTiling.tiles(width, height)) {
                val inputs = sources.mapIndexed { k, s -> s.tile(tile, shifts[k]) }
                val fused = ExposureFusion.fuse(inputs)
                writeTile(bitmap, turn, tile, fused)
                gm?.let { writeGain(it, turn, tile, inputs, fused, exposures) }
            }
            if (gm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                bitmap.gainmap = Gainmap(gm).apply {
                    setRatioMin(1f, 1f, 1f)
                    setRatioMax(GainMapMath.MAX_RATIO, GainMapMath.MAX_RATIO, GainMapMath.MAX_RATIO)
                    setGamma(1f, 1f, 1f)
                    setEpsilonSdr(0f, 0f, 0f)
                    setEpsilonHdr(0f, 0f, 0f)
                    minDisplayRatioForHdrTransition = 1f
                    displayRatioForFullHdr = GainMapMath.MAX_RATIO
                }
            }
            FileOutputStream(output).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encoding failed" }
                out.fd.sync()
            }
        } finally {
            bitmap.recycle()
            gm?.recycle()
        }
    }

    /** Shift of each frame against [reference] in full-resolution pixels, from downsampled grey planes. */
    private fun align(sources: List<I420>, reference: Int): List<Pair<Int, Int>> {
        val factor = maxOf(1, (maxOf(sources[0].width, sources[0].height) + ALIGN_SIDE - 1) / ALIGN_SIDE)
        val small = sources.map { it.lumaPlane(factor) }
        return small.mapIndexed { k, p ->
            if (k == reference) {
                0 to 0
            } else {
                val (dx, dy) = ExposureAlignment.shift(small[reference], p, ALIGN_RADIUS)
                dx * factor to dy * factor
            }
        }
    }

    private fun writeTile(bitmap: Bitmap, turn: Turn, tile: FusionTile, fused: RgbTile) {
        val ox = tile.outX - tile.readX
        val oy = tile.outY - tile.readY
        val rect = turn.rect(tile.outX, tile.outY, tile.outWidth, tile.outHeight)
        val pixels = IntArray(rect.w * rect.h)
        bitmap.getPixels(pixels, 0, rect.w, rect.x, rect.y, rect.w, rect.h)
        for (y in 0 until tile.outHeight) {
            for (x in 0 until tile.outWidth) {
                val ax = tile.outX + x
                val ay = tile.outY + y
                val a = FusionTiling.alpha(tile, ax, ay)
                val i = (oy + y) * fused.width + ox + x
                val (tx, ty) = turn.point(ax, ay)
                val idx = (ty - rect.y) * rect.w + (tx - rect.x)
                val old = pixels[idx]
                pixels[idx] = Color.rgb(
                    mix(Color.red(old), fused.r.data[i], a),
                    mix(Color.green(old), fused.g.data[i], a),
                    mix(Color.blue(old), fused.b.data[i], a)
                )
            }
        }
        bitmap.setPixels(pixels, 0, rect.w, rect.x, rect.y, rect.w, rect.h)
    }

    private fun mix(old: Int, new: Float, a: Float): Int = (old * (1f - a) + new * 255f * a + 0.5f).toInt().coerceIn(0, 255)

    @Suppress("LongParameterList")
    private fun writeGain(gm: Bitmap, turn: Turn, tile: FusionTile, inputs: List<RgbTile>, fused: RgbTile, exposures: DoubleArray) {
        val values = FloatArray(inputs.size)
        for (y in tile.outY until tile.outY + tile.outHeight) {
            for (x in tile.outX until tile.outX + tile.outWidth) {
                val (tx, ty) = turn.point(x, y)
                if (tx % GAIN_STEP != 0 || ty % GAIN_STEP != 0) continue
                val i = (y - tile.readY) * fused.width + (x - tile.readX)
                for (k in inputs.indices) values[k] = luma(inputs[k], i)
                val radiance = GainMapMath.radiance(values, exposures)
                val sdr = GainMapMath.srgbToLinear(luma(fused, i))
                gm.setPixel(tx / GAIN_STEP, ty / GAIN_STEP, Color.argb(GainMapMath.encode(GainMapMath.gain(radiance, sdr)), 0, 0, 0))
            }
        }
    }

    private fun luma(t: RgbTile, i: Int): Float = 0.2126f * t.r.data[i] + 0.7152f * t.g.data[i] + 0.0722f * t.b.data[i]

    /** Deletes the frame files. */
    fun clear() {
        workDir.listFiles()?.forEach { it.delete() }
    }

    /** One mapped I420 frame. */
    private class I420(private val data: MappedByteBuffer, val width: Int, val height: Int) {
        private val uOffset = width * height
        private val vOffset = uOffset + (width / 2) * (height / 2)

        fun y(x: Int, y: Int): Int = data.get(y * width + x).toInt() and 0xFF

        /** Grey plane downsampled by [factor] (nearest), 0..1. */
        fun lumaPlane(factor: Int): Plane {
            val w = width / factor
            val h = height / factor
            val p = Plane(w, h)
            for (yy in 0 until h) for (xx in 0 until w) p[xx, yy] = y(xx * factor, yy * factor) / 255f
            return p
        }

        /** The read area of [tile], moved by [shift] (edges clamped), as RGB 0..1 (BT.601 full range). */
        fun tile(tile: FusionTile, shift: Pair<Int, Int>): RgbTile {
            val out = RgbTile(tile.readWidth, tile.readHeight)
            val cw = width / 2
            for (ty in 0 until tile.readHeight) {
                val sy = (tile.readY + ty + shift.second).coerceIn(0, height - 1)
                for (tx in 0 until tile.readWidth) {
                    val sx = (tile.readX + tx + shift.first).coerceIn(0, width - 1)
                    val yy = y(sx, sy).toFloat()
                    val c = (sy / 2).coerceAtMost(height / 2 - 1) * cw + (sx / 2).coerceAtMost(cw - 1)
                    val u = (data.get(uOffset + c).toInt() and 0xFF) - 128f
                    val v = (data.get(vOffset + c).toInt() and 0xFF) - 128f
                    val i = ty * tile.readWidth + tx
                    out.r.data[i] = ((yy + 1.402f * v) / 255f).coerceIn(0f, 1f)
                    out.g.data[i] = ((yy - 0.344136f * u - 0.714136f * v) / 255f).coerceIn(0f, 1f)
                    out.b.data[i] = ((yy + 1.772f * u) / 255f).coerceIn(0f, 1f)
                }
            }
            return out
        }
    }

    /** The clockwise turn of [rotation] degrees from sensor (w x h) to upright output. */
    private class Turn(rotation: Int, private val w: Int, private val h: Int) {
        private val r = ((rotation % 360) + 360) % 360
        val width = if (r == 90 || r == 270) h else w
        val height = if (r == 90 || r == 270) w else h

        fun point(x: Int, y: Int): Pair<Int, Int> = when (r) {
            90 -> (h - 1 - y) to x
            180 -> (w - 1 - x) to (h - 1 - y)
            270 -> y to (w - 1 - x)
            else -> x to y
        }

        fun rect(x: Int, y: Int, rw: Int, rh: Int): Rect {
            val (ax, ay) = point(x, y)
            val (bx, by) = point(x + rw - 1, y + rh - 1)
            return Rect(minOf(ax, bx), minOf(ay, by), abs(bx - ax) + 1, abs(by - ay) + 1)
        }

        private fun abs(v: Int) = if (v < 0) -v else v
    }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    companion object {
        /** Gain map resolution: one sample per 4 x 4 pixels, as most Ultra HDR encoders do. */
        const val GAIN_STEP = 4
        private const val ALIGN_SIDE = 512
        private const val ALIGN_RADIUS = 8
    }
}
