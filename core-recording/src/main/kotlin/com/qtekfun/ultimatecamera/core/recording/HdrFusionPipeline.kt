package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Gainmap
import android.media.Image
import android.os.Build
import android.util.Log
import com.qtekfun.ultimatecamera.core.gpu.hdr.BandSink
import com.qtekfun.ultimatecamera.core.gpu.hdr.ExposureAlignment
import com.qtekfun.ultimatecamera.core.gpu.hdr.FastExposureFusion
import com.qtekfun.ultimatecamera.core.gpu.hdr.FusionTone
import com.qtekfun.ultimatecamera.core.gpu.hdr.GainMapMath
import com.qtekfun.ultimatecamera.core.gpu.hdr.GpuExposureFusion
import com.qtekfun.ultimatecamera.core.gpu.hdr.LowStage
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RowSource
import com.qtekfun.ultimatecamera.core.gpu.hdr.ShiftedRows
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * Our own HDR photo (D64): the bracket frames are written to the work directory as I420 as they arrive (the camera
 * buffers are let go at once), then mapped back read-only, aligned and fused with [FastExposureFusion] across all cores
 * into one upright bitmap that is JPEG-encoded. On Android 14+ the bitmap carries a gain map ([GainMapMath]) so the JPEG
 * is Ultra HDR. With `render` the base rendering and style hook ([PhotoFinisher.render]) run before encoding.
 * Memory: the output bitmap plus small images and one row band per core; the frames stay in the page cache, not on the
 * heap. [workDir] is wiped before and after every shot ([HdrWorkDir]).
 */
class HdrFusionPipeline(workDir: File) {
    val work = HdrWorkDir(workDir)

    /** Writes [image] (YUV_420_888) as I420 to frame file [index]. */
    fun writeFrame(index: Int, image: Image): File {
        val file = File(work.dir.apply { mkdirs() }, "frame$index${HdrWorkDir.FRAME_SUFFIX}")
        val w = image.width
        val h = image.height
        FileOutputStream(file).buffered(WRITE_BUFFER).use { out ->
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
        }
        return file
    }

    private fun readRow(buffer: ByteBuffer, offset: Int, pixelStride: Int, count: Int, into: ByteArray) {
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
     * Runs on the GPU ([GpuExposureFusion]) and falls back to the CPU ([FastExposureFusion]) when GLES fails. Returns
     * the time of each stage, also logged as `FUSION_STAGE …`.
     */
    @Suppress("LongParameterList", "TooGenericExceptionCaught") // Any GL failure falls back to the CPU path.
    fun fuse(
        frames: List<File>,
        relativeExposures: List<Double>,
        reference: Int,
        width: Int,
        height: Int,
        rotation: Int,
        gainMap: Boolean,
        quality: Int,
        output: File,
        render: Boolean = false
    ): FusionTimings {
        val timings = FusionTimings()
        val maps = timings.measure("read") {
            frames.map { f -> RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) } }
        }
        val turn = Turn(rotation, width, height)
        val bitmap = Bitmap.createBitmap(turn.width, turn.height, Bitmap.Config.ARGB_8888)
        val withGain = gainMap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val step = FastExposureFusion.GAIN_STEP
        val gm = GainSize((turn.width + step - 1) / step, (turn.height + step - 1) / step)
        try {
            val gainPixels = try {
                fuseOnGpu(maps, relativeExposures, reference, width, height, rotation, withGain, gm, bitmap, timings)
            } catch (e: Exception) {
                Log.w(TAG, "GPU fusion failed, using the CPU: ${e.javaClass.simpleName}: ${e.message}")
                timings.path = "cpu"
                fuseOnCpu(maps, relativeExposures, reference, width, height, turn, withGain, gm, bitmap, timings)
            }
            // The fused image is already toned to the 0 EV exposure (FusionTone); the base rendering and the style
            // hook run on it; the gain map stays as merged (relative to the untoned fusion, so slightly conservative).
            if (render) timings.measure("render") { PhotoFinisher.render(bitmap) }
            timings.measure("gainmap") { if (gainPixels != null) attachGainmap(bitmap, gainPixels, gm) }
            timings.measure("jpeg") {
                FileOutputStream(output).use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encoding failed" } }
            }
        } finally {
            bitmap.recycle()
        }
        Log.i(TAG, timings.toString())
        return timings
    }

    private class GainSize(val width: Int, val height: Int)

    @Suppress("LongParameterList")
    private fun fuseOnGpu(
        maps: List<MappedByteBuffer>,
        exposures: List<Double>,
        reference: Int,
        width: Int,
        height: Int,
        rotation: Int,
        withGain: Boolean,
        gm: GainSize,
        bitmap: Bitmap,
        timings: FusionTimings
    ): IntArray? {
        val gpu = timings.measure("gl") { GpuExposureFusion(width, height) }
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            timings.measure("upload") { maps.forEachIndexed { k, m -> gpu.upload(k, m) } }
            val low = timings.measure("downscale") { maps.indices.map(gpu::downscale) }
            val lowShifts = timings.measure("align") { LowStage.shifts(low, reference) }
            val stage = timings.measure("fuse") { LowStage.of(low, lowShifts, pool) }
            timings.align = stage.shifts.toString()
            val shifts = stage.shifts.map { (dx, dy) -> dx * gpu.factor to dy * gpu.factor }
            timings.measure("upload") { gpu.setLow(stage.fused, stage.aligned, stage.weights) }
            val tone = FusionTone.of(FusionTone.luma(stage.fused), FusionTone.luma(stage.aligned[reference]))
            timings.measure("upsample") {
                gpu.render(shifts, rotation) { y0, rows, argb ->
                    tone.apply(argb, 0, bitmap.width * rows)
                    timings.measure("rotate") { bitmap.setPixels(argb, 0, bitmap.width, 0, y0, bitmap.width, rows) }
                }
            }
            return if (withGain) timings.measure("gainmap") { gpu.gainMap(shifts, rotation, exposures.toDoubleArray(), gm.width, gm.height) } else null
        } finally {
            pool.shutdown()
            gpu.release()
        }
    }

    @Suppress("LongParameterList")
    private fun fuseOnCpu(
        maps: List<MappedByteBuffer>,
        exposures: List<Double>,
        reference: Int,
        width: Int,
        height: Int,
        turn: Turn,
        withGain: Boolean,
        gm: GainSize,
        bitmap: Bitmap,
        timings: FusionTimings
    ): IntArray? {
        val sources = maps.map { I420(it, width, height) }
        val shifts = timings.measure("align") { align(sources, reference) }
        timings.align = shifts.toString()
        val aligned = sources.mapIndexed { k, s -> ShiftedRows(s, width, height, shifts[k].first, shifts[k].second) }
        val gainPixels = if (withGain) IntArray(gm.width * gm.height) else null
        var tone: FusionTone? = null
        val sink = BandSink { y0, rows, r, g, b, gain ->
            val t = requireNotNull(tone)
            timings.measure("rotate") {
                writeBand(bitmap, turn, y0, rows, width) { i -> OPAQUE or (t[byte(r[i])] shl 16) or (t[byte(g[i])] shl 8) or t[byte(b[i])] }
                if (gain != null && gainPixels != null) writeGain(gainPixels, gm.width, turn, y0, rows, width, gain)
            }
        }
        timings.measure("upsample") {
            FastExposureFusion(width, height).fuse(aligned, if (withGain) exposures.toDoubleArray() else null, sink) { fused, low ->
                tone = FusionTone.of(FusionTone.luma(fused), FusionTone.luma(low[reference]))
            }
        }
        return gainPixels
    }

    private fun attachGainmap(bitmap: Bitmap, pixels: IntArray, gm: GainSize) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val contents = Bitmap.createBitmap(gm.width, gm.height, Bitmap.Config.ALPHA_8)
        contents.setPixels(pixels, 0, gm.width, 0, 0, gm.width, gm.height)
        bitmap.gainmap = Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(GainMapMath.MAX_RATIO, GainMapMath.MAX_RATIO, GainMapMath.MAX_RATIO)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            minDisplayRatioForHdrTransition = 1f
            displayRatioForFullHdr = GainMapMath.MAX_RATIO
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
                val (dx, dy) = ExposureAlignment.shiftPyramid(small[reference], p, ALIGN_RADIUS)
                dx * factor to dy * factor
            }
        }
    }

    /** Writes source rows [y0] until [y0] + [rows] (colours from [color] by band index) at their turned place. */
    @Suppress("LongParameterList")
    private inline fun writeBand(bitmap: Bitmap, turn: Turn, y0: Int, rows: Int, width: Int, color: (Int) -> Int) {
        val rect = turn.rect(0, y0, width, rows)
        val pixels = IntArray(rect.w * rect.h)
        for (row in 0 until rows) {
            for (x in 0 until width) {
                val y = y0 + row
                pixels[(turn.ty(x, y) - rect.y) * rect.w + (turn.tx(x, y) - rect.x)] = color(row * width + x)
            }
        }
        synchronized(bitmap) { bitmap.setPixels(pixels, 0, rect.w, rect.x, rect.y, rect.w, rect.h) }
    }

    @Suppress("LongParameterList")
    private fun writeGain(into: IntArray, gmWidth: Int, turn: Turn, y0: Int, rows: Int, width: Int, gain: FloatArray) {
        val step = FastExposureFusion.GAIN_STEP
        for (row in 0 until rows) {
            val y = y0 + row
            if (y % step != 0) continue
            for (x in 0 until width step step) {
                into[(turn.ty(x, y) / step) * gmWidth + turn.tx(x, y) / step] = Color.argb(GainMapMath.encode(gain[row * width + x]), 0, 0, 0)
            }
        }
    }

    private fun byte(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    /** One mapped I420 frame, read as RGB rows (BT.601 full range); each thread reads through its own view. */
    private class I420(data: MappedByteBuffer, val width: Int, val height: Int) : RowSource {
        private val uOffset = width * height
        private val vOffset = uOffset + (width / 2) * (height / 2)
        private val view = ThreadLocal.withInitial { RowScratch(data.duplicate(), ByteArray(width), ByteArray(width / 2), ByteArray(width / 2)) }
        private val shared = data

        fun y(x: Int, y: Int): Int = shared.get(y * width + x).toInt() and 0xFF

        /** Grey plane downsampled by [factor] (nearest), 0..1. */
        fun lumaPlane(factor: Int): Plane {
            val w = width / factor
            val h = height / factor
            val p = Plane(w, h)
            for (yy in 0 until h) for (xx in 0 until w) p[xx, yy] = y(xx * factor, yy * factor) / 255f
            return p
        }

        override fun readRow(y: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
            val s = view.get()
            val cw = width / 2
            val cy = (y / 2).coerceAtMost(height / 2 - 1)
            s.buffer.position(y * width)
            s.buffer.get(s.y, 0, width)
            s.buffer.position(uOffset + cy * cw)
            s.buffer.get(s.u, 0, cw)
            s.buffer.position(vOffset + cy * cw)
            s.buffer.get(s.v, 0, cw)
            for (x in 0 until width) {
                val c = (x / 2).coerceAtMost(cw - 1)
                val yy = (s.y[x].toInt() and 0xFF).toFloat()
                val u = (s.u[c].toInt() and 0xFF) - 128f
                val v = (s.v[c].toInt() and 0xFF) - 128f
                r[x] = ((yy + 1.402f * v) * INV_255).coerceIn(0f, 1f)
                g[x] = ((yy - 0.344136f * u - 0.714136f * v) * INV_255).coerceIn(0f, 1f)
                b[x] = ((yy + 1.772f * u) * INV_255).coerceIn(0f, 1f)
            }
        }

        private class RowScratch(val buffer: ByteBuffer, val y: ByteArray, val u: ByteArray, val v: ByteArray)
    }

    /** The clockwise turn of [rotation] degrees from sensor (w x h) to upright output. */
    private class Turn(rotation: Int, private val w: Int, private val h: Int) {
        private val r = ((rotation % 360) + 360) % 360
        val width = if (r == 90 || r == 270) h else w
        val height = if (r == 90 || r == 270) w else h

        /** Turned column of source pixel (x, y); no allocation, it runs for every pixel. */
        fun tx(x: Int, y: Int): Int = when (r) {
            90 -> h - 1 - y
            180 -> w - 1 - x
            270 -> y
            else -> x
        }

        /** Turned row of source pixel (x, y). */
        fun ty(x: Int, y: Int): Int = when (r) {
            90 -> x
            180 -> h - 1 - y
            270 -> w - 1 - x
            else -> y
        }

        fun rect(x: Int, y: Int, rw: Int, rh: Int): Rect {
            val ax = tx(x, y)
            val ay = ty(x, y)
            val bx = tx(x + rw - 1, y + rh - 1)
            val by = ty(x + rw - 1, y + rh - 1)
            return Rect(minOf(ax, bx), minOf(ay, by), abs(bx - ax) + 1, abs(by - ay) + 1)
        }

        private fun abs(v: Int) = if (v < 0) -v else v
    }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    companion object {
        private const val TAG = "UcHdrBench"
        private const val ALIGN_SIDE = 512
        private const val ALIGN_RADIUS = 8
        private const val WRITE_BUFFER = 256 * 1024
        private const val INV_255 = 1f / 255f
        private const val OPAQUE = -0x1000000
    }
}
