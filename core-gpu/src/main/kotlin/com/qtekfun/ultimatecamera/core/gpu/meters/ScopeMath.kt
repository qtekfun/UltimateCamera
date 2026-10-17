package com.qtekfun.ultimatecamera.core.gpu.meters

import kotlin.math.abs
import kotlin.math.min

/**
 * CPU reference (the specification) of the analysis meters computed by `ScopeRenderer`.
 *
 * The GPU first renders the preview into a small RGBA8 *analysis image* (one bilinear tap per cell, see [downsample]).
 * Every scope then bins the analysis image with **integer** arithmetic on 8-bit code values, which the GLSL ES 3.00
 * vertex shaders reproduce bit for bit, so GPU and CPU counts are equal (not just close):
 * - luma `Y' = 2126 R + 7152 G + 722 B` (Rec. 709 × 10 000), level `(Y' + 5000) / 10000` in 0..255;
 * - vectorscope `Cb = (B − Y) / 1.8556`, `Cr = (R − Y) / 1.5748` (Rec. 709), cell `⌊(C + 0.5) × size⌋`, computed as an
 *   exact integer fraction that fits 32-bit unsigned maths for sizes up to 256.
 *
 * Counts are accumulated on the GPU by additive blending into textures whose texels saturate at a *capacity* (2048 in a
 * half-float target, 255 in an RGBA8 one); the histogram spreads its samples over enough layers that no texel saturates.
 * Images are ARGB_8888 arrays in GL order (row 0 at the bottom).
 */
object ScopeMath {
    const val LEVELS = 256

    /** Channel order inside every count texel. */
    const val R = 0
    const val G = 1
    const val B = 2
    const val Y = 3

    /** The analysis image: one bilinear sample at the centre of each of the [width]×[height] cells, rounded to 8 bits. */
    fun downsample(source: IntArray, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): IntArray {
        val sampler = MonitorOverlayMath.Sampler(source, sourceWidth, sourceHeight)
        val rgb = FloatArray(3)
        return IntArray(width * height) { i ->
            sampler.sample((i % width + 0.5f) / width, (i / width + 0.5f) / height, rgb)
            Rgb8.argb(rgb)
        }
    }

    /**
     * Layered histogram as the GPU texture holds it: `LEVELS × layers` texels of four counts (R, G, B, Y); sample `i` of
     * the analysis image goes to layer `i % layers`. Index `((layer × LEVELS) + level) × 4 + channel`.
     */
    fun histogram(analysis: IntArray, layers: Int): IntArray {
        val counts = IntArray(LEVELS * layers * 4)
        for (i in analysis.indices) {
            val p = analysis[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val base = (i % layers) * LEVELS
            counts[(base + r) * 4 + R]++
            counts[(base + g) * 4 + G]++
            counts[(base + b) * 4 + B]++
            counts[(base + ScopeBins.lumaLevel(r, g, b)) * 4 + Y]++
        }
        return counts
    }

    /** Sums the layers of [layered] into `LEVELS × 4` counts (index `level × 4 + channel`). */
    fun sumLayers(layered: IntArray, layers: Int, out: IntArray = IntArray(LEVELS * 4)): IntArray {
        out.fill(0)
        for (layer in 0 until layers) {
            for (j in 0 until LEVELS * 4) out[j] += layered[layer * LEVELS * 4 + j]
        }
        return out
    }

    /** Waveform counts, `width × LEVELS` texels of (R, G, B, Y), index `(level × width + column) × 4 + channel`. */
    fun waveform(analysis: IntArray, width: Int, capacity: Int): IntArray {
        val counts = IntArray(width * LEVELS * 4)
        for (i in analysis.indices) {
            val p = analysis[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val column = i % width
            counts[(r * width + column) * 4 + R]++
            counts[(g * width + column) * 4 + G]++
            counts[(b * width + column) * 4 + B]++
            counts[(ScopeBins.lumaLevel(r, g, b) * width + column) * 4 + Y]++
        }
        for (j in counts.indices) counts[j] = min(counts[j], capacity)
        return counts
    }

    /** Vectorscope counts, `size × size`, index `y × size + x` (x = Cb, y = Cr). */
    fun vectorscope(analysis: IntArray, size: Int, capacity: Int): IntArray {
        val counts = IntArray(size * size)
        for (p in analysis) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            counts[ScopeBins.vectorscopeY(r, g, b, size) * size + ScopeBins.vectorscopeX(r, g, b, size)]++
        }
        for (j in counts.indices) counts[j] = min(counts[j], capacity)
        return counts
    }

    /** Histogram bar height scale: a bar is full height at `histogramScale` times the mean bin count. */
    fun histogramHeightScale(sampleCount: Int, settings: ScopeSettings): Float = LEVELS / (sampleCount * settings.histogramScale)

    fun waveformGain(analysisHeight: Int, settings: ScopeSettings): Float = settings.waveformGain * 16f / analysisHeight

    fun vectorscopeGain(sampleCount: Int, size: Int, settings: ScopeSettings): Float = settings.vectorscopeGain * size * size / (2f * sampleCount)
}

/** Exact integer binning shared by the GPU scatter shaders and the CPU reference (see [ScopeMath]). */
object ScopeBins {
    /** 255 × 2 × (1 − KB) × 10 000 and 255 × 2 × (1 − KR) × 10 000: the exact Cb and Cr denominators. */
    const val CB_DENOMINATOR = 4_731_780L
    const val CR_DENOMINATOR = 4_015_740L

    /** Angle of the skin-tone ("I") line on a Rec. 709 vectorscope, degrees counter-clockwise from +Cb. */
    const val SKIN_TONE_ANGLE_DEGREES = 123f

    fun lumaScaled(r: Int, g: Int, b: Int): Int = 2126 * r + 7152 * g + 722 * b

    fun lumaLevel(r: Int, g: Int, b: Int): Int = (lumaScaled(r, g, b) + 5000) / 10000

    fun vectorscopeX(r: Int, g: Int, b: Int, size: Int): Int = cell(b * 10000L - lumaScaled(r, g, b), CB_DENOMINATOR, size)

    fun vectorscopeY(r: Int, g: Int, b: Int, size: Int): Int = cell(r * 10000L - lumaScaled(r, g, b), CR_DENOMINATOR, size)

    /** Position of a colour on the vectorscope, 0..1 on both axes (graticule targets for the UI). */
    fun vectorscopePosition(r: Float, g: Float, b: Float): Pair<Float, Float> {
        val y = Luma.of(r, g, b)
        return ((b - y) / 1.8556f + 0.5f) to ((r - y) / 1.5748f + 0.5f)
    }

    /** Layers the histogram needs so that no texel of capacity [capacity] receives more than [capacity] samples. */
    fun histogramLayers(sampleCount: Int, capacity: Int): Int = maxOf(1, (sampleCount + capacity - 1) / capacity)

    private fun cell(numerator: Long, denominator: Long, size: Int): Int =
        (((2 * numerator + denominator) * size) / (2 * denominator)).toInt().coerceAtMost(size - 1)
}

/** CPU reference of the scope displays drawn over black (the GPU output is premultiplied, so it is the same). */
object ScopeDisplayMath {
    const val COLOR_RED = 0xFFFF0000.toInt()
    const val COLOR_GREEN = 0xFF00FF00.toInt()
    const val COLOR_BLUE = 0xFF0000FF.toInt()
    const val COLOR_LUMA = 0xFFE6E6E6.toInt()
    const val COLOR_VECTOR = 0xFFA0FFA0.toInt()

    /**
     * The histogram display ([width]×[height]) drawn over black: column `x` shows level `x × 256 / width`; a channel
     * lights pixel row `y` when `(y + 0.5) / height < min(1, count × scale)`. [margins] gets the distance to that edge.
     */
    fun histogramDisplay(totals: IntArray, sampleCount: Int, settings: ScopeSettings, width: Int, height: Int, margins: FloatArray? = null): IntArray {
        val scale = ScopeMath.histogramHeightScale(sampleCount, settings)
        val channels = if (settings.histogramMode == HistogramMode.LUMA) intArrayOf(ScopeMath.Y) else intArrayOf(ScopeMath.R, ScopeMath.G, ScopeMath.B)
        val colors = intArrayOf(COLOR_RED, COLOR_GREEN, COLOR_BLUE, COLOR_LUMA)
        val rgb = FloatArray(3)
        return IntArray(width * height) { i ->
            val level = (i / width + 0.5f) / height
            val bin = (i % width) * ScopeMath.LEVELS / width
            rgb.fill(0f)
            var margin = Float.MAX_VALUE
            for (c in channels) {
                val h = min(1f, totals[bin * 4 + c] * scale)
                margin = min(margin, abs(level - h))
                if (level < h) Rgb8.addColor(rgb, colors[c], 1f)
            }
            if (margins != null) margins[i] = margin
            Rgb8.argb(rgb)
        }
    }

    /** The waveform display over black; parade splits the width into three panels (R, G, B). */
    fun waveformDisplay(counts: IntArray, analysisWidth: Int, analysisHeight: Int, settings: ScopeSettings, width: Int, height: Int): IntArray {
        val gain = ScopeMath.waveformGain(analysisHeight, settings)
        val rgb = FloatArray(3)
        return IntArray(width * height) { i ->
            val px = i % width
            val level = (i / width) * ScopeMath.LEVELS / height
            rgb.fill(0f)
            when (settings.waveformMode) {
                WaveformMode.LUMA -> Rgb8.addColor(rgb, COLOR_LUMA, intensity(counts, analysisWidth, px * analysisWidth / width, level, ScopeMath.Y, gain))
                WaveformMode.RGB_OVERLAY -> {
                    val column = px * analysisWidth / width
                    Rgb8.addColor(rgb, COLOR_RED, intensity(counts, analysisWidth, column, level, ScopeMath.R, gain))
                    Rgb8.addColor(rgb, COLOR_GREEN, intensity(counts, analysisWidth, column, level, ScopeMath.G, gain))
                    Rgb8.addColor(rgb, COLOR_BLUE, intensity(counts, analysisWidth, column, level, ScopeMath.B, gain))
                }
                WaveformMode.RGB_PARADE -> {
                    val panel = px * 3 / width
                    val x0 = panel * width / 3
                    val x1 = (panel + 1) * width / 3
                    val column = (px - x0) * analysisWidth / (x1 - x0)
                    val color = intArrayOf(COLOR_RED, COLOR_GREEN, COLOR_BLUE)[panel]
                    Rgb8.addColor(rgb, color, intensity(counts, analysisWidth, column, level, panel, gain))
                }
            }
            Rgb8.argb(rgb)
        }
    }

    /** The vectorscope display over black: cell `(x × size / width, y × size / height)`. */
    fun vectorscopeDisplay(counts: IntArray, size: Int, sampleCount: Int, settings: ScopeSettings, width: Int, height: Int): IntArray {
        val gain = ScopeMath.vectorscopeGain(sampleCount, size, settings)
        val rgb = FloatArray(3)
        return IntArray(width * height) { i ->
            val cx = (i % width) * size / width
            val cy = (i / width) * size / height
            rgb.fill(0f)
            Rgb8.addColor(rgb, COLOR_VECTOR, min(1f, counts[cy * size + cx] * gain))
            Rgb8.argb(rgb)
        }
    }

    private fun intensity(counts: IntArray, analysisWidth: Int, column: Int, level: Int, channel: Int, gain: Float): Float =
        min(1f, counts[(level * analysisWidth + column) * 4 + channel] * gain)
}
