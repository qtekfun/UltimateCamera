package com.qtekfun.ultimatecamera.core.gpu.hdr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.exp

/**
 * The technical base rendering (D74) of every JPEG that is not the phone maker's own processing: tuned so the owner's
 * washed-out comparison shot lands near the phone's own camera (blacks at the floor, more global and local contrast).
 * No colour styling: creative looks are the style engine's, applied after this (the style hook).
 */
data class RenderParams(val clarity: Float, val contrast: Float, val blackPoint: Float) {
    companion object {
        val BASE = RenderParams(clarity = 0.2f, contrast = 0.25f, blackPoint = 0.6f)
    }
}

/**
 * The global tone curve: `f(v) = v + c·v(1−v)(2v−1)`, an S that is monotonic for c ≤ 1, keeps 0 and 1, steepens the
 * mid-tones by 1 + c/2 and flattens both ends to slope 1 − c, so highlights roll off instead of clipping.
 */
object ToneCurve {
    fun s(v: Float, contrast: Float): Float = v + contrast * v * (1f - v) * (2f * v - 1f)

    /** Levels: [blackPoint] maps to 0, 1 stays 1. */
    fun levels(v: Float, blackPoint: Float): Float = (v - blackPoint) / (1f - blackPoint)
}

/**
 * Applies [RenderParams] to a full-size photo in two streaming passes over its rows, in bands across all cores, with
 * memory bounded by a small luma image (long side ≤ 512) plus one band per worker:
 * 1. luma histogram (for the adaptive black point: [RenderParams.blackPoint] × the 0.5th percentile, at most 0.15) and
 *    the box-averaged luma;
 * 2. per pixel: clarity (luma minus its large-radius blur, σ ≈ 1.1 % of the long side, added to R, G, B), levels,
 *    the S curve per channel (colour is left to the style engine).
 */
class PhotoLook(private val width: Int, private val height: Int, private val params: RenderParams = RenderParams.BASE) {
    private val factor = maxOf(1, (maxOf(width, height) + MAX_LOW_SIDE - 1) / MAX_LOW_SIDE)
    private val lowW = (width + factor - 1) / factor
    private val lowH = (height + factor - 1) / factor

    /** The black point the last [apply] used (for tests and the photo's metadata). */
    var blackPoint = 0f
        private set

    fun apply(source: RowSource, sink: BandSink, threads: Int = Runtime.getRuntime().availableProcessors()) {
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val (low, histogram) = measure(source, pool)
            blackPoint = (percentile(histogram, BLACK_PERCENTILE) * params.blackPoint).coerceIn(0f, MAX_BLACK_POINT)
            val blurred = blur(low, SIGMA_FRACTION * maxOf(lowW, lowH))
            render(source, blurred, sink, pool)
        } finally {
            pool.shutdown()
        }
    }

    private fun measure(source: RowSource, pool: ExecutorService): Pair<Plane, LongArray> {
        val low = Plane(lowW, lowH)
        val jobs = (0 until lowH).map { ly ->
            Callable {
                val r = FloatArray(width)
                val g = FloatArray(width)
                val b = FloatArray(width)
                val hist = LongArray(BINS)
                val y0 = ly * factor
                val y1 = minOf(y0 + factor, height)
                for (y in y0 until y1) {
                    source.readRow(y, r, g, b)
                    for (x in 0 until width) {
                        val l = luma(r[x], g[x], b[x])
                        hist[(l * (BINS - 1) + 0.5f).toInt().coerceIn(0, BINS - 1)]++
                        low.data[ly * lowW + x / factor] += l
                    }
                }
                for (lx in 0 until lowW) low.data[ly * lowW + lx] /= ((minOf(lx * factor + factor, width) - lx * factor) * (y1 - y0)).toFloat()
                hist
            }
        }
        val histogram = LongArray(BINS)
        pool.invokeAll(jobs).forEach { f -> f.get().forEachIndexed { i, c -> histogram[i] += c } }
        return low to histogram
    }

    @Suppress("LongMethod")
    private fun render(source: RowSource, blurred: Plane, sink: BandSink, pool: ExecutorService) {
        val bp = blackPoint
        val clarity = params.clarity
        val contrast = params.contrast
        val jobs = (0 until height step BAND).map { y0 ->
            Callable {
                val rows = minOf(BAND, height - y0)
                val outR = FloatArray(rows * width)
                val outG = FloatArray(rows * width)
                val outB = FloatArray(rows * width)
                val r = FloatArray(width)
                val g = FloatArray(width)
                val b = FloatArray(width)
                val lowRow = FloatArray(lowW)
                for (row in 0 until rows) {
                    val y = y0 + row
                    source.readRow(y, r, g, b)
                    verticalLerp(blurred, y, lowRow)
                    for (x in 0 until width) {
                        val fx = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, (lowW - 1).toFloat())
                        val i0 = fx.toInt()
                        val i1 = (i0 + 1).coerceAtMost(lowW - 1)
                        val base = lowRow[i0] + (lowRow[i1] - lowRow[i0]) * (fx - i0)
                        val d = clarity * (luma(r[x], g[x], b[x]) - base)
                        val o = row * width + x
                        outR[o] = tone(r[x] + d, bp, contrast)
                        outG[o] = tone(g[x] + d, bp, contrast)
                        outB[o] = tone(b[x] + d, bp, contrast)
                    }
                }
                sink.band(y0, rows, outR, outG, outB, null)
            }
        }
        pool.invokeAll(jobs).forEach { it.get() }
    }

    private fun verticalLerp(p: Plane, y: Int, into: FloatArray) {
        val fy = ((y + 0.5f) / factor - 0.5f).coerceIn(0f, (lowH - 1).toFloat())
        val y0 = fy.toInt()
        val y1 = (y0 + 1).coerceAtMost(lowH - 1)
        val t = fy - y0
        for (x in 0 until lowW) into[x] = p.data[y0 * lowW + x] + (p.data[y1 * lowW + x] - p.data[y0 * lowW + x]) * t
    }

    companion object {
        /** Longest side of the luma image the clarity blur runs on. */
        const val MAX_LOW_SIDE = 512

        /** Clarity radius: σ as a fraction of the long side (6 px on a 533-px image, 45 px on 4000). */
        const val SIGMA_FRACTION = 0.0113f
        const val BLACK_PERCENTILE = 0.005
        const val MAX_BLACK_POINT = 0.15f
        const val BINS = 1024
        const val BAND = 64

        /** BT.601 luma, as JPEG's YCbCr and the owner's measurements use. */
        fun luma(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

        fun tone(v: Float, blackPoint: Float, contrast: Float): Float = ToneCurve.s(ToneCurve.levels(v, blackPoint).coerceIn(0f, 1f), contrast)

        /** The value below which [fraction] of the pixels lie. */
        fun percentile(histogram: LongArray, fraction: Double): Float {
            val total = histogram.sum()
            var acc = 0L
            for (i in histogram.indices) {
                acc += histogram[i]
                if (acc >= fraction * total) return i.toFloat() / (histogram.size - 1)
            }
            return 1f
        }

        /** Separable Gaussian blur of [p] with [sigma] pixels (edges clamped). */
        fun blur(p: Plane, sigma: Float): Plane {
            val radius = (sigma * 3f).toInt().coerceAtLeast(1)
            val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2f * sigma * sigma)) }
            val sum = kernel.sum()
            for (i in kernel.indices) kernel[i] /= sum
            val tmp = Plane(p.width, p.height)
            for (y in 0 until p.height) {
                for (x in 0 until p.width) {
                    var acc = 0f
                    for (k in kernel.indices) acc += kernel[k] * p[(x + k - radius).coerceIn(0, p.width - 1), y]
                    tmp[x, y] = acc
                }
            }
            val out = Plane(p.width, p.height)
            for (y in 0 until p.height) {
                for (x in 0 until p.width) {
                    var acc = 0f
                    for (k in kernel.indices) acc += kernel[k] * tmp[x, (y + k - radius).coerceIn(0, p.height - 1)]
                    out[x, y] = acc
                }
            }
            return out
        }
    }
}
