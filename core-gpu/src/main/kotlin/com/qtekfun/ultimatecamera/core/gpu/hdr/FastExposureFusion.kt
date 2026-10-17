package com.qtekfun.ultimatecamera.core.gpu.hdr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A full-size exposure read one row at a time as RGB 0..1. Must be safe to call from several threads at once. */
fun interface RowSource {
    fun readRow(y: Int, r: FloatArray, g: FloatArray, b: FloatArray)
}

/** Receives fused rows [y0] until [y0] + rows, row-major in [r], [g], [b] (and [gain] when asked). Called from workers. */
fun interface BandSink {
    @Suppress("LongParameterList")
    fun band(y0: Int, rows: Int, r: FloatArray, g: FloatArray, b: FloatArray, gain: FloatArray?)
}

/**
 * Fast exposure fusion for full-size photos (D64 follow-up; the tiled reference [ExposureFusion] took over two minutes
 * for 12 MP on the Pixel 8). Mertens fusion is done exactly on frames box-averaged by [factor]: weights and the whole
 * Laplacian pyramid live at that size. The full-size result is that fused base, upsampled, plus each frame's own detail
 * (the frame minus its upsampled box average) weighted by its upsampled, normalised weight, which is the pyramid blend
 * with the finest bands' weights smoothed. Two streaming passes over the frames, in row bands across all cores;
 * memory is the small images plus one band per worker. With [exposures] each band also gets the Ultra HDR gain
 * ([GainMapMath]) on the [GAIN_STEP] grid (other entries stay 0).
 */
class FastExposureFusion(private val width: Int, private val height: Int, private val factor: Int = DEFAULT_FACTOR) {
    private val lowW = (width + factor - 1) / factor
    private val lowH = (height + factor - 1) / factor

    /** [onLow] sees the fused small image and the small frames before the full-size pass (e.g. to fit a tone curve). */
    @Suppress("LongMethod")
    fun fuse(
        sources: List<RowSource>,
        exposures: DoubleArray?,
        sink: BandSink,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        onLow: (fused: RgbTile, low: List<RgbTile>) -> Unit = { _, _ -> }
    ) {
        require(sources.isNotEmpty()) { "no exposures" }
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val low = sources.map { boxAverage(it, pool) }
            val weights = low.map(ExposureFusion::weights)
            ExposureFusion.normalise(weights)
            val fused = ExposureFusion.fuse(low, LOW_LEVELS, pool)
            onLow(fused, low)
            detailPass(sources, low, weights, fused, exposures, sink, pool)
        } finally {
            pool.shutdown()
        }
    }

    /** The frame averaged over [factor] x [factor] blocks (edge blocks average what they cover). */
    private fun boxAverage(source: RowSource, pool: ExecutorService): RgbTile {
        val out = RgbTile(lowW, lowH)
        val jobs = (0 until lowH).map { ly ->
            Callable {
                val r = FloatArray(width)
                val g = FloatArray(width)
                val b = FloatArray(width)
                val y0 = ly * factor
                val y1 = minOf(y0 + factor, height)
                val base = ly * lowW
                for (y in y0 until y1) {
                    source.readRow(y, r, g, b)
                    for (x in 0 until width) {
                        val i = base + x / factor
                        out.r.data[i] += r[x]
                        out.g.data[i] += g[x]
                        out.b.data[i] += b[x]
                    }
                }
                for (lx in 0 until lowW) {
                    val n = ((minOf(lx * factor + factor, width) - lx * factor) * (y1 - y0)).toFloat()
                    out.r.data[base + lx] /= n
                    out.g.data[base + lx] /= n
                    out.b.data[base + lx] /= n
                }
            }
        }
        pool.invokeAll(jobs).forEach { it.get() }
        return out
    }

    @Suppress("LongParameterList")
    private fun detailPass(
        sources: List<RowSource>,
        low: List<RgbTile>,
        weights: List<Plane>,
        fused: RgbTile,
        exposures: DoubleArray?,
        sink: BandSink,
        pool: ExecutorService
    ) {
        val xs = Interp(width, lowW, factor)
        // Planes interpolated per row: fused r,g,b, then per frame r,g,b,weight.
        val planes = fused.channels + low.indices.flatMap { k -> low[k].channels + weights[k] }
        val jobs = (0 until height step BAND).map { y0 ->
            Callable {
                val rows = minOf(BAND, height - y0)
                val n = sources.size
                val outR = FloatArray(rows * width)
                val outG = FloatArray(rows * width)
                val outB = FloatArray(rows * width)
                val gain = if (exposures != null) FloatArray(rows * width) else null
                val fr = Array(n) { FloatArray(width) }
                val fg = Array(n) { FloatArray(width) }
                val fb = Array(n) { FloatArray(width) }
                val rowLow = Array(planes.size) { FloatArray(lowW) }
                val values = FloatArray(n)
                for (row in 0 until rows) {
                    val y = y0 + row
                    for (k in 0 until n) sources[k].readRow(y, fr[k], fg[k], fb[k])
                    verticalLerp(planes, y, rowLow)
                    for (x in 0 until width) {
                        val i0 = xs.i0[x]
                        val i1 = xs.i1[x]
                        val t = xs.t[x]
                        var r = lerp(rowLow[0], i0, i1, t)
                        var g = lerp(rowLow[1], i0, i1, t)
                        var b = lerp(rowLow[2], i0, i1, t)
                        for (k in 0 until n) {
                            val p = 3 + k * 4
                            val w = lerp(rowLow[p + 3], i0, i1, t)
                            r += w * (fr[k][x] - lerp(rowLow[p], i0, i1, t))
                            g += w * (fg[k][x] - lerp(rowLow[p + 1], i0, i1, t))
                            b += w * (fb[k][x] - lerp(rowLow[p + 2], i0, i1, t))
                        }
                        val o = row * width + x
                        outR[o] = r.coerceIn(0f, 1f)
                        outG[o] = g.coerceIn(0f, 1f)
                        outB[o] = b.coerceIn(0f, 1f)
                    }
                    if (gain != null && exposures != null && y % GAIN_STEP == 0) {
                        for (x in 0 until width step GAIN_STEP) {
                            val o = row * width + x
                            for (k in 0 until n) values[k] = luma(fr[k][x], fg[k][x], fb[k][x])
                            val sdr = GainMapMath.srgbToLinear(luma(outR[o], outG[o], outB[o]))
                            gain[o] = GainMapMath.gain(GainMapMath.radiance(values, exposures), sdr)
                        }
                    }
                }
                sink.band(y0, rows, outR, outG, outB, gain)
            }
        }
        pool.invokeAll(jobs).forEach { it.get() }
    }

    /** Each low plane interpolated vertically at full-size row [y] into [into]. */
    private fun verticalLerp(planes: List<Plane>, y: Int, into: Array<FloatArray>) {
        val fy = ((y + 0.5f) / factor - 0.5f).coerceIn(0f, (lowH - 1).toFloat())
        val y0 = fy.toInt()
        val y1 = (y0 + 1).coerceAtMost(lowH - 1)
        val t = fy - y0
        for (p in planes.indices) {
            val d = planes[p].data
            val a = y0 * lowW
            val c = y1 * lowW
            val out = into[p]
            for (x in 0 until lowW) out[x] = d[a + x] + (d[c + x] - d[a + x]) * t
        }
    }

    /** Horizontal bilinear indices and weights from full-size columns to low columns (block centres aligned). */
    private class Interp(width: Int, lowW: Int, factor: Int) {
        val i0 = IntArray(width)
        val i1 = IntArray(width)
        val t = FloatArray(width)

        init {
            for (x in 0 until width) {
                val fx = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, (lowW - 1).toFloat())
                i0[x] = fx.toInt()
                i1[x] = (i0[x] + 1).coerceAtMost(lowW - 1)
                t[x] = fx - i0[x]
            }
        }
    }

    companion object {
        /** Low-resolution step: 12 MP becomes 500 x 375, where the full Mertens pyramid costs little. */
        const val DEFAULT_FACTOR = 8

        /** Pyramid levels at the low size (its finest bands are already 8 px wide at full size). */
        const val LOW_LEVELS = 5

        /** The gain is computed on every [GAIN_STEP]-th pixel of every [GAIN_STEP]-th row (a 1/4-size gain map). */
        const val GAIN_STEP = 4

        /** Rows per work item. */
        const val BAND = 64

        private fun lerp(a: FloatArray, i0: Int, i1: Int, t: Float): Float = a[i0] + (a[i1] - a[i0]) * t

        fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}

/**
 * The small-image stage shared by the CPU and GPU fusions: [low] frames aligned to the [reference] one ([shifts] in
 * low pixels; each frame is read that far further), their normalised Mertens [weights] and the [fused] small image.
 */
class LowStage(val shifts: List<Pair<Int, Int>>, val aligned: List<RgbTile>, val weights: List<Plane>, val fused: RgbTile) {
    companion object {
        /** Search radius in low pixels: ±4 at factor 8 is ±32 px at full size, more than a hand-held burst moves. */
        const val ALIGN_RADIUS = 4

        /** Shift of each [low] frame against the [reference] one, in low pixels (median-threshold bitmaps, coarse to fine). */
        fun shifts(low: List<RgbTile>, reference: Int, radius: Int = ALIGN_RADIUS): List<Pair<Int, Int>> {
            val luma = low.map(::luma)
            return luma.mapIndexed { k, p -> if (k == reference) 0 to 0 else ExposureAlignment.shiftPyramid(luma[reference], p, radius) }
        }

        fun of(low: List<RgbTile>, shifts: List<Pair<Int, Int>>, pool: ExecutorService): LowStage {
            val aligned = low.mapIndexed { k, t -> translate(t, shifts[k].first, shifts[k].second) }
            val weights = aligned.map(ExposureFusion::weights)
            ExposureFusion.normalise(weights)
            return LowStage(shifts, aligned, weights, ExposureFusion.fuse(aligned, FastExposureFusion.LOW_LEVELS, pool))
        }

        fun luma(t: RgbTile): Plane = Plane(
            t.width,
            t.height,
            FloatArray(t.width * t.height) {
                FastExposureFusion.luma(t.r.data[it], t.g.data[it], t.b.data[it])
            }
        )

        /** [t] read [dx], [dy] pixels further, edges clamped. */
        fun translate(t: RgbTile, dx: Int, dy: Int): RgbTile {
            if (dx == 0 && dy == 0) return t
            val out = RgbTile(t.width, t.height)
            for (y in 0 until t.height) {
                val sy = (y + dy).coerceIn(0, t.height - 1)
                for (x in 0 until t.width) {
                    val i = sy * t.width + (x + dx).coerceIn(0, t.width - 1)
                    val o = y * t.width + x
                    out.r.data[o] = t.r.data[i]
                    out.g.data[o] = t.g.data[i]
                    out.b.data[o] = t.b.data[i]
                }
            }
            return out
        }
    }
}

/** [source] read [dx], [dy] pixels further (edges clamped): how an aligned frame is read. */
class ShiftedRows(private val source: RowSource, private val width: Int, private val height: Int, private val dx: Int, private val dy: Int) : RowSource {
    private val scratch = ThreadLocal.withInitial { Array(3) { FloatArray(width) } }

    override fun readRow(y: Int, r: FloatArray, g: FloatArray, b: FloatArray) {
        val sy = (y + dy).coerceIn(0, height - 1)
        if (dx == 0) {
            source.readRow(sy, r, g, b)
            return
        }
        val s = scratch.get()
        source.readRow(sy, s[0], s[1], s[2])
        for (x in 0 until width) {
            val sx = (x + dx).coerceIn(0, width - 1)
            r[x] = s[0][sx]
            g[x] = s[1][sx]
            b[x] = s[2][sx]
        }
    }
}
