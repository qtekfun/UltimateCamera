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
 * What the full-size pass of [PhotoLook] needs once pass 1 and the small-image stage are done: the image and low
 * sizes, the edge-aware [base], the [tone] and the image's saturation gain.
 */
class LookPlan(val width: Int, val height: Int, val factor: Int, val params: RenderParams, val base: GuidedBase, val tone: ToneMap, val saturation: Float) {
    val lowW: Int get() = base.a.width
    val lowH: Int get() = base.a.height
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
    /** Block size of the low image and its size. */
    val factor = maxOf(1, (maxOf(width, height) + MAX_LOW_SIDE - 1) / MAX_LOW_SIDE)
    val lowW = (width + factor - 1) / factor
    val lowH = (height + factor - 1) / factor

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
            val plan = prepare(source, pool)
            val t2 = System.nanoTime()
            render(source, plan.base, plan.tone, sink, pool)
            timings = timings + ("render" to (System.nanoTime() - t2) / NS_PER_MS)
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Pass 1 and the small-image stage of [apply] (records "measure", "tone" and "base" in [timings]): what the
     * full-size pass needs, for the GPU rendering ([GpuPhotoLook], D90) or [render] on the CPU. [guided] may compute
     * the edge-aware base elsewhere (the GPU); null from it, or no [guided], runs [GuidedBase.of] on the CPU.
     */
    fun prepare(
        source: RowSource,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        measured: (() -> Measured?)? = null,
        guided: ((low: Plane, radius: Int, eps: Float) -> GuidedBase?)? = null
    ): LookPlan {
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            return prepare(source, pool, measured, guided)
        } finally {
            pool.shutdown()
        }
    }

    private fun prepare(
        source: RowSource,
        pool: ExecutorService,
        measured: (() -> Measured?)? = null,
        guided: ((Plane, Int, Float) -> GuidedBase?)? = null
    ): LookPlan {
        val t0 = System.nanoTime()
        val m = measured?.invoke() ?: measure(source, pool)
        val low = m.low
        val histogram = m.histogram
        val meanSaturation = m.meanSaturation
        val t1 = System.nanoTime()
        saturation = saturationGain(meanSaturation, params)
        val tone = ToneMap(params, percentile(histogram, BLACK_PERCENTILE), percentile(histogram, params.whitePercentile))
        blackPoint = tone.blackPoint
        whiteGain = tone.whiteGain
        val radius = (params.baseRadius * maxOf(lowW, lowH)).toInt().coerceAtLeast(1)
        val t2 = System.nanoTime()
        val base = guided?.invoke(low, radius, params.baseEdge) ?: GuidedBase.of(low, radius, params.baseEdge, pool)
        val t3 = System.nanoTime()
        timings = linkedMapOf("measure" to (t1 - t0) / NS_PER_MS, "tone" to (t2 - t1) / NS_PER_MS, "base" to (t3 - t2) / NS_PER_MS)
        return LookPlan(width, height, factor, params, base, tone, saturation)
    }

    /**
     * The full-size pass of [apply] on the CPU for a [plan] from [prepare], rows [from] to the end (records "render" in
     * [timings]). Each row depends only on itself and the plan, so a GPU pass that stopped part-way can be finished here.
     */
    fun render(plan: LookPlan, source: RowSource, sink: BandSink, from: Int = 0, threads: Int = Runtime.getRuntime().availableProcessors()) {
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
        try {
            val t0 = System.nanoTime()
            render(source, plan.base, plan.tone, sink, pool, from)
            timings = timings + ("render" to (System.nanoTime() - t0) / NS_PER_MS)
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Low log luma, full-resolution luma histogram and the mean saturation of the box-averaged image. Work items are
     * runs of low rows (a few per core, one histogram each) and the loops index arrays directly: the first photo after
     * start runs this mostly interpreted, where every call and allocation per pixel or per bin shows (D90).
     */
    @Suppress("LongMethod", "NestedBlockDepth")
    private fun measure(source: RowSource, pool: ExecutorService): Measured {
        val low = Plane(lowW, lowH)
        val saturationSums = DoubleArray(lowH)
        val per = maxOf(1, (lowH + MEASURE_JOBS - 1) / MEASURE_JOBS)
        val jobs = (0 until lowH step per).map { first ->
            Callable {
                val r = FloatArray(width)
                val g = FloatArray(width)
                val b = FloatArray(width)
                val hist = LongArray(BINS)
                val sr = FloatArray(lowW)
                val sg = FloatArray(lowW)
                val sb = FloatArray(lowW)
                val lows = FloatArray(lowW)
                val data = low.data
                for (ly in first until minOf(first + per, lowH)) {
                    sr.fill(0f)
                    sg.fill(0f)
                    sb.fill(0f)
                    lows.fill(0f)
                    val y0 = ly * factor
                    val y1 = minOf(y0 + factor, height)
                    for (y in y0 until y1) {
                        source.readRow(y, r, g, b)
                        var x = 0
                        while (x < width) {
                            val lx = x / factor
                            val end = minOf(x + factor, width)
                            while (x < end) {
                                val l = 0.299f * r[x] + 0.587f * g[x] + 0.114f * b[x]
                                var bin = (l * (BINS - 1) + 0.5f).toInt()
                                if (bin < 0) {
                                    bin = 0
                                } else if (bin > BINS - 1) {
                                    bin = BINS - 1
                                }
                                hist[bin]++
                                lows[lx] += LogLuma.of(l)
                                sr[lx] += r[x]
                                sg[lx] += g[x]
                                sb[lx] += b[x]
                                x++
                            }
                        }
                    }
                    var sat = 0.0
                    val o = ly * lowW
                    for (lx in 0 until lowW) {
                        val n = ((minOf(lx * factor + factor, width) - lx * factor) * (y1 - y0)).toFloat()
                        data[o + lx] = lows[lx] / n
                        sat += saturationOf(sr[lx] / n, sg[lx] / n, sb[lx] / n)
                    }
                    saturationSums[ly] = sat
                }
                hist
            }
        }
        val histogram = LongArray(BINS)
        for (f in pool.invokeAll(jobs)) {
            val h = f.get()
            for (i in 0 until BINS) histogram[i] += h[i]
        }
        return Measured(low, histogram, (saturationSums.sum() / (lowW * lowH)).toFloat())
    }

    /** What pass 1 finds: the low log-luma image, the full-size luma histogram ([BINS]) and the mean saturation. */
    class Measured(val low: Plane, val histogram: LongArray, val meanSaturation: Float) {
        companion object {
            /**
             * From a GPU pass 1 ([GpuPhotoMeasure]): per low pixel (mean log luma, mean R, G, B), row-major, and the
             * histogram; the mean saturation is summed as the CPU pass sums it (per low row, then the rows).
             */
            fun of(lowW: Int, lowH: Int, low: FloatArray, histogram: IntArray): Measured {
                require(low.size == lowW * lowH * 4 && histogram.size == BINS) { "measure sizes" }
                val plane = Plane(lowW, lowH)
                var sum = 0.0
                for (ly in 0 until lowH) {
                    var sat = 0.0
                    for (lx in 0 until lowW) {
                        val i = ly * lowW + lx
                        plane.data[i] = low[i * 4]
                        sat += saturationOf(low[i * 4 + 1], low[i * 4 + 2], low[i * 4 + 3])
                    }
                    sum += sat
                }
                return Measured(plane, LongArray(BINS) { histogram[it].toLong() and 0xFFFFFFFFL }, (sum / (lowW * lowH)).toFloat())
            }
        }
    }

    @Suppress("LongMethod", "LongParameterList")
    private fun render(source: RowSource, base: GuidedBase, tone: ToneMap, sink: BandSink, pool: ExecutorService, from: Int = 0) {
        val gain = saturation
        val jobs = (from until height step BAND).map { y0 ->
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
        internal const val MIN_RATIO_LUMA = 1f / 1024f
        private const val NS_PER_MS = 1_000_000L
        const val BLACK_PERCENTILE = 0.005
        const val MAX_BLACK_POINT = 0.15f
        const val BINS = 1024
        const val BAND = 64

        /** Work items of pass 1 (runs of low rows). */
        private const val MEASURE_JOBS = 32

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
        internal val SKIN_HUE = (5f / 60f)..(45f / 60f)
        internal val SKIN_SATURATION = 0.18f..0.6f

        /** Hue 190–250° for blue-dominant colours is 240 + 60 × (r − g) / range degrees. */
        internal val SKY_HUE = (-50f / 60f)..(10f / 60f)
        internal const val SKY_BRIGHTNESS = 0.5f
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
