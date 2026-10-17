package com.qtekfun.ultimatecamera.core.gpu.hdr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The technical base rendering (D74, D84) of every JPEG that is not the phone maker's own processing: tuned so our
 * photos land near the phone's own camera (white at about 243, deep shadows opened by local tone mapping, blacks at the
 * floor, local contrast kept). No colour styling: creative looks are the style engine's, applied after this (the style
 * hook).
 */
@Suppress("LongParameterList")
data class RenderParams(
    val contrast: Float,
    /** Display luma the input's darkest 0.5 % is brought to, through the whole curve (0 = no black point). */
    val blackTarget: Float,
    /** Adaptive saturation gain = scale × (mean input saturation)^exponent, within 1..[saturationMax]. */
    val saturationScale: Float = 0f,
    val saturationExponent: Float = 0f,
    val saturationMax: Float = 1f,
    /** Share of the saturation boost that skin tones and bright blue sky get (they look wrong pushed). */
    val skinProtection: Float = 0.5f,
    val skyProtection: Float = 0.7f,
    /** Display luma the input's [whitePercentile] is brought to (0 = no white gain). */
    val whiteTarget: Float = 0f,
    val whitePercentile: Double = 0.99,
    /** Most stops of exposure the white gain may add. */
    val maxWhiteGain: Float = 0f,
    /** Linear light above which highlights roll off into the white gain's headroom. */
    val shoulderKnee: Float = 0.75f,
    /** Local tone mapping: the edge-aware base below [shadowPivot] (log2 linear) is lifted by [shadowStrength] × the gap. */
    val shadowPivot: Float = -2.5f,
    val shadowStrength: Float = 0f,
    val maxShadowLift: Float = 0f,
    /** Log2 linear luma below which lift and detail fade out (true black and noise stay down). */
    val shadowFloor: Float = -10f,
    /** Local contrast: the detail (log luma minus its edge-aware base) is multiplied by 1 + [detail]. */
    val detail: Float = 0f,
    /** Guided-filter window radius as a fraction of the low image's long side, and its edge threshold (stops²). */
    val baseRadius: Float = 0.04f,
    val baseEdge: Float = 0.25f
) {
    companion object {
        /**
         * Fitted on the owner's comparison shots against the phone's own camera (indoor room, white wall, dark
         * corridor through a door; earlier a street scene and a second outdoor pair): saturation and spread within
         * about 15 %, white at about 243, blacks at p1 ≈ 7–15.
         */
        val BASE = RenderParams(
            contrast = 0.4f,
            blackTarget = 0.03f,
            saturationScale = 0.5f,
            saturationExponent = -0.6f,
            saturationMax = 2.6f,
            whiteTarget = 0.953f,
            maxWhiteGain = 1f,
            shadowStrength = 0.1f,
            maxShadowLift = 1.2f,
            detail = 0.2f
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
 * memory bounded by a small log-luma image (long side ≤ 512) plus one band per worker:
 * 1. luma histogram (adaptive black point: [RenderParams.blackPoint] × the 0.5th percentile, at most 0.15; scene
 *    white: the [RenderParams.whitePercentile]), the box-averaged log2 linear luma and the mean saturation; then, on
 *    the small image, the edge-aware base of the local tone mapping ([GuidedBase]) and the tone ([ToneMap]);
 * 2. per pixel: the luma mapped by [ToneMap] (white gain, shadow lift on the base, detail boost, shoulder, levels, S
 *    curve), applied to R, G, B as a ratio (hue and saturation kept, out-of-gamut colours pulled towards the luma
 *    instead of clipped), then an adaptive saturation boost (more for dull scenes, less on skin and sky). Creative
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

    /** The white gain in stops the last [apply] used. */
    var whiteGain = 0f
        private set

    /** Milliseconds of the last [apply]'s stages: measure (pass 1), base (small image), render (pass 2). */
    var timings: Map<String, Long> = emptyMap()
        private set

    fun apply(source: RowSource, sink: BandSink, threads: Int = Runtime.getRuntime().availableProcessors()) {
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val t0 = System.nanoTime()
            val (low, histogram, meanSaturation) = measure(source, pool)
            val t1 = System.nanoTime()
            saturation = saturationGain(meanSaturation, params)
            val tone = ToneMap(params, percentile(histogram, BLACK_PERCENTILE), percentile(histogram, params.whitePercentile))
            blackPoint = tone.blackPoint
            whiteGain = tone.whiteGain
            val radius = (params.baseRadius * maxOf(lowW, lowH)).toInt().coerceAtLeast(1)
            val base = GuidedBase.of(low, radius, params.baseEdge)
            val t2 = System.nanoTime()
            render(source, base, tone, sink, pool)
            val t3 = System.nanoTime()
            timings = linkedMapOf("measure" to (t1 - t0) / NS_PER_MS, "base" to (t2 - t1) / NS_PER_MS, "render" to (t3 - t2) / NS_PER_MS)
        } finally {
            pool.shutdown()
        }
    }

    /** Low log luma, full-resolution luma histogram and the mean saturation of the box-averaged image. */
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
                        low.data[ly * lowW + x / factor] += LogLuma.of(l)
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
    private fun render(source: RowSource, base: GuidedBase, tone: ToneMap, sink: BandSink, pool: ExecutorService) {
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
                val rowA = FloatArray(lowW)
                val rowB = FloatArray(lowW)
                for (row in 0 until rows) {
                    val y = y0 + row
                    source.readRow(y, r, g, b)
                    verticalLerp(base.a, y, rowA)
                    verticalLerp(base.b, y, rowB)
                    for (x in 0 until width) {
                        val fx = ((x + 0.5f) / factor - 0.5f).coerceIn(0f, (lowW - 1).toFloat())
                        val i0 = fx.toInt()
                        val i1 = (i0 + 1).coerceAtMost(lowW - 1)
                        val t = fx - i0
                        val a = rowA[i0] + (rowA[i1] - rowA[i0]) * t
                        val bb = rowB[i0] + (rowB[i1] - rowB[i0]) * t
                        val y1 = luma(r[x], g[x], b[x])
                        val l = LogLuma.of(y1)
                        val v = tone.map(l, a * l + bb)
                        val o = row * width + x
                        var cr = v
                        var cg = v
                        var cb = v
                        if (y1 >= MIN_RATIO_LUMA) {
                            val k = v / y1
                            cr = r[x] * k
                            cg = g[x] * k
                            cb = b[x] * k
                            val max = maxOf(cr, cg, cb)
                            if (max > 1f) {
                                // Out of gamut: pull the colour towards its luma (kept) instead of clipping a channel.
                                val s = (1f - v) / (max - v)
                                cr = v + (cr - v) * s
                                cg = v + (cg - v) * s
                                cb = v + (cb - v) * s
                            }
                        }
                        cr = tone.finish(cr)
                        cg = tone.finish(cg)
                        cb = tone.finish(cb)
                        val yo = luma(cr, cg, cb)
                        val k = protectedGain(cr, cg, cb, gain, params)
                        outR[o] = (yo + k * (cr - yo)).coerceIn(0f, 1f)
                        outG[o] = (yo + k * (cg - yo)).coerceIn(0f, 1f)
                        outB[o] = (yo + k * (cb - yo)).coerceIn(0f, 1f)
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
        /** Longest side of the log-luma image the edge-aware base is computed on. */
        const val MAX_LOW_SIDE = 512

        /** Below this input luma the ratio is meaningless: the pixel takes the mapped luma as grey. */
        private const val MIN_RATIO_LUMA = 1f / 1024f
        private const val NS_PER_MS = 1_000_000L
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
    }
}
