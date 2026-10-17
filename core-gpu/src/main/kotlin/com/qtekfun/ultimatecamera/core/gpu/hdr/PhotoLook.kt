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
@Suppress("LongParameterList")
data class RenderParams(
    val clarity: Float,
    val contrast: Float,
    val blackPoint: Float,
    /** Adaptive saturation gain = scale × (mean input saturation)^exponent, within 1..[saturationMax]. */
    val saturationScale: Float = 0f,
    val saturationExponent: Float = 0f,
    val saturationMax: Float = 1f,
    /** Share of the saturation boost that skin tones and bright blue sky get (they look wrong pushed). */
    val skinProtection: Float = 0.5f,
    val skyProtection: Float = 0.7f
) {
    companion object {
        /**
         * Fitted on the owner's two comparison pairs: a dull scene (mean saturation about 0.05) gets about ×2, a
         * colourful one (about 0.11) about ×1.35; both land within 5 % of the phone's own camera in saturation and
         * contrast, with blacks at p1 ≈ 14.
         */
        val BASE = RenderParams(
            clarity = 0.2f,
            contrast = 0.3f,
            blackPoint = 0.2f,
            saturationScale = 0.366f,
            saturationExponent = -0.6f,
            saturationMax = 2.2f
        )
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
 *    the S curve per channel, then an adaptive saturation boost (more for dull scenes, less on skin and sky). Creative
 *    colour is left to the style engine.
 */
class PhotoLook(private val width: Int, private val height: Int, private val params: RenderParams = RenderParams.BASE) {
    private val factor = maxOf(1, (maxOf(width, height) + MAX_LOW_SIDE - 1) / MAX_LOW_SIDE)
    private val lowW = (width + factor - 1) / factor
    private val lowH = (height + factor - 1) / factor

    /** The black point the last [apply] used (for tests and the photo's metadata). */
    var blackPoint = 0f
        private set

    /** The saturation gain the last [apply] used (before skin and sky protection). */
    var saturation = 1f
        private set

    fun apply(source: RowSource, sink: BandSink, threads: Int = Runtime.getRuntime().availableProcessors()) {
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val (low, histogram, meanSaturation) = measure(source, pool)
            blackPoint = (percentile(histogram, BLACK_PERCENTILE) * params.blackPoint).coerceIn(0f, MAX_BLACK_POINT)
            saturation = saturationGain(meanSaturation, params)
            val blurred = blur(low, SIGMA_FRACTION * maxOf(lowW, lowH))
            render(source, blurred, sink, pool)
        } finally {
            pool.shutdown()
        }
    }

    /** Low luma, full-resolution luma histogram and the mean saturation of the box-averaged image. */
    private fun measure(source: RowSource, pool: ExecutorService): Triple<Plane, LongArray, Float> {
        val low = Plane(lowW, lowH)
        val saturationSums = DoubleArray(lowH)
        val jobs = (0 until lowH).map { ly ->
            Callable {
                val r = FloatArray(width)
                val g = FloatArray(width)
                val b = FloatArray(width)
                val hist = LongArray(BINS)
                val sum = Array(3) { FloatArray(lowW) }
                val y0 = ly * factor
                val y1 = minOf(y0 + factor, height)
                for (y in y0 until y1) {
                    source.readRow(y, r, g, b)
                    for (x in 0 until width) {
                        val l = luma(r[x], g[x], b[x])
                        hist[(l * (BINS - 1) + 0.5f).toInt().coerceIn(0, BINS - 1)]++
                        low.data[ly * lowW + x / factor] += l
                        sum[0][x / factor] += r[x]
                        sum[1][x / factor] += g[x]
                        sum[2][x / factor] += b[x]
                    }
                }
                var sat = 0.0
                for (lx in 0 until lowW) {
                    val n = ((minOf(lx * factor + factor, width) - lx * factor) * (y1 - y0)).toFloat()
                    low.data[ly * lowW + lx] /= n
                    sat += saturationOf(sum[0][lx] / n, sum[1][lx] / n, sum[2][lx] / n)
                }
                saturationSums[ly] = sat
                hist
            }
        }
        val histogram = LongArray(BINS)
        pool.invokeAll(jobs).forEach { f -> f.get().forEachIndexed { i, c -> histogram[i] += c } }
        return Triple(low, histogram, (saturationSums.sum() / (lowW * lowH)).toFloat())
    }

    @Suppress("LongMethod")
    private fun render(source: RowSource, blurred: Plane, sink: BandSink, pool: ExecutorService) {
        val bp = blackPoint
        val clarity = params.clarity
        val contrast = params.contrast
        val gain = saturation
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
                        val cr = tone(r[x] + d, bp, contrast)
                        val cg = tone(g[x] + d, bp, contrast)
                        val cb = tone(b[x] + d, bp, contrast)
                        val k = protectedGain(cr, cg, cb, gain, params)
                        val l = luma(cr, cg, cb)
                        outR[o] = (l + k * (cr - l)).coerceIn(0f, 1f)
                        outG[o] = (l + k * (cg - l)).coerceIn(0f, 1f)
                        outB[o] = (l + k * (cb - l)).coerceIn(0f, 1f)
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

        /** HSV-style saturation (max − min) / max, 0 for black. */
        fun saturationOf(r: Float, g: Float, b: Float): Float {
            val max = maxOf(r, g, b)
            return if (max <= 0f) 0f else (max - minOf(r, g, b)) / max
        }

        /** The image's saturation gain for its [meanSaturation] (dull images more, colourful ones about 1). */
        fun saturationGain(meanSaturation: Float, params: RenderParams): Float {
            if (params.saturationScale <= 0f) return 1f
            if (meanSaturation <= MIN_SATURATION) return params.saturationMax
            val gain = params.saturationScale * Math.pow(meanSaturation.toDouble(), params.saturationExponent.toDouble()).toFloat()
            return gain.coerceIn(1f, params.saturationMax)
        }

        /**
         * [gain] reduced for skin (red > green > blue, hue 5–45°, saturation 0.18–0.6) and bright blue sky (blue the
         * largest, hue 190–250°, brightest channel above 0.5).
         */
        fun protectedGain(r: Float, g: Float, b: Float, gain: Float, params: RenderParams): Float {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val range = max - min
            if (range <= 0f || gain <= 1f) return gain
            val s = range / max
            val skinHue = r > g && g > b && (g - b) / range in SKIN_HUE
            if (skinHue && s in SKIN_SATURATION) return 1f + (gain - 1f) * params.skinProtection
            val skyHue = b >= r && b >= g && (r - g) / range in SKY_HUE
            if (skyHue && max > SKY_BRIGHTNESS) return 1f + (gain - 1f) * params.skyProtection
            return gain
        }

        /** Hue 5–45° for red-dominant colours is 60 × (g − b) / range degrees. */
        private val SKIN_HUE = (5f / 60f)..(45f / 60f)
        private val SKIN_SATURATION = 0.18f..0.6f

        /** Hue 190–250° for blue-dominant colours is 240 + 60 × (r − g) / range degrees. */
        private val SKY_HUE = (-50f / 60f)..(10f / 60f)
        private const val SKY_BRIGHTNESS = 0.5f
        private const val MIN_SATURATION = 0.005f

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
