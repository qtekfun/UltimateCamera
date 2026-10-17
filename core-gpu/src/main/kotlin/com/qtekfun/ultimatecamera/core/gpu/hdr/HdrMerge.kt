package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Translation between two exposures by median-threshold bitmaps (Ward, 2003): each grey image is thresholded at its
 * own median, which does not depend on the exposure, and the shift with the fewest differing bits (pixels near the
 * median excluded) wins. Run on downsampled planes; scale the result back up.
 */
object ExposureAlignment {
    private const val NOISE_BAND = 4f / 255f

    fun shift(reference: Plane, moving: Plane, radius: Int): Pair<Int, Int> {
        val refMedian = median(reference)
        val movMedian = median(moving)
        var best = 0 to 0
        var bestScore = Long.MAX_VALUE
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val score = differences(reference, refMedian, moving, movMedian, dx, dy)
                if (score < bestScore || (score == bestScore && abs(dx) + abs(dy) < abs(best.first) + abs(best.second))) {
                    bestScore = score
                    best = dx to dy
                }
            }
        }
        return best
    }

    /** Differing bits when [moving] is read at (x + dx, y + dy), normalised per 10 000 compared pixels. */
    private fun differences(ref: Plane, refMedian: Float, mov: Plane, movMedian: Float, dx: Int, dy: Int): Long {
        var diff = 0L
        var count = 0L
        val ys = maxOf(0, -dy) until minOf(ref.height, mov.height - dy)
        val xs = maxOf(0, -dx) until minOf(ref.width, mov.width - dx)
        for (y in ys) {
            for (x in xs) {
                val a = ref[x, y]
                val b = mov[x + dx, y + dy]
                val compared = abs(a - refMedian) >= NOISE_BAND && abs(b - movMedian) >= NOISE_BAND
                if (compared) count++
                if (compared && (a > refMedian) != (b > movMedian)) diff++
            }
        }
        return if (count == 0L) Long.MAX_VALUE else diff * SCORE_SCALE / count
    }

    private const val SCORE_SCALE = 10_000L

    fun median(p: Plane): Float = p.data.copyOf().also { it.sort() }[p.data.size / 2]
}

/**
 * The Ultra HDR gain map of a fused photo (D64, Android 14 `Gainmap`): the scene's linear radiance is estimated from
 * the bracket (each frame's linear value divided by its relative exposure, weighted away from clipping and noise),
 * and the gain is how much brighter than the fused SDR pixel an HDR display may show it, from 1 to [MAX_RATIO].
 */
object GainMapMath {
    /** Most headroom the map asks for: 2 stops, what the ±2 EV bracket can really recover. */
    const val MAX_RATIO = 4f

    fun srgbToLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    /** Trust in a frame's value: zero when clipped or black, highest at mid-grey. */
    fun confidence(v: Float): Float {
        val u = (2f * v - 1f) * (2f * v - 1f)
        val u3 = u * u * u
        return (1f - u3 * u3).coerceAtLeast(0f)
    }

    /**
     * Linear radiance at the reference exposure from one pixel's gamma-encoded [values] (one per frame) and the frames'
     * [relativeExposures]; when every frame is clipped, the darkest frame's value is used.
     */
    fun radiance(values: FloatArray, relativeExposures: DoubleArray): Float {
        var sum = 0f
        var weights = 0f
        for (k in values.indices) {
            val w = confidence(values[k])
            sum += w * srgbToLinear(values[k]) / relativeExposures[k].toFloat()
            weights += w
        }
        if (weights > 0f) return sum / weights
        val darkest = relativeExposures.indices.minByOrNull { relativeExposures[it] } ?: 0
        return srgbToLinear(values[darkest]) / relativeExposures[darkest].toFloat()
    }

    /** Gain from the fused SDR linear luminance to the radiance, clamped to 1..[MAX_RATIO]. */
    fun gain(radiance: Float, sdrLinear: Float): Float = if (sdrLinear <= 1e-4f) 1f else (radiance / sdrLinear).coerceIn(1f, MAX_RATIO)

    /** The gain map byte for [gain] (log-encoded between 1 and [MAX_RATIO], gamma 1). */
    fun encode(gain: Float): Int = (ln(gain.toDouble()) / ln(MAX_RATIO.toDouble()) * 255.0 + 0.5).toInt().coerceIn(0, 255)
}
