package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * The tile grid of a fused photo (D64): tiles step by [step], each writes [step] + [overlap] pixels (cross-faded with
 * the previous tile over [overlap]) and reads [margin] more on every side so the pyramid sees context. Memory per
 * tile is bounded by (step + overlap + 2·margin)² whatever the photo size.
 */
data class FusionTile(
    val readX: Int,
    val readY: Int,
    val readWidth: Int,
    val readHeight: Int,
    val outX: Int,
    val outY: Int,
    val outWidth: Int,
    val outHeight: Int,
    val fadeLeft: Boolean,
    val fadeTop: Boolean
)

object FusionTiling {
    const val STEP = 256
    const val OVERLAP = 32
    const val MARGIN = 64

    fun tiles(width: Int, height: Int, step: Int = STEP, overlap: Int = OVERLAP, margin: Int = MARGIN): List<FusionTile> = buildList {
        var y0 = 0
        while (y0 < height) {
            var x0 = 0
            while (x0 < width) {
                val outW = minOf(step + overlap, width - x0)
                val outH = minOf(step + overlap, height - y0)
                val rx = (x0 - margin).coerceAtLeast(0)
                val ry = (y0 - margin).coerceAtLeast(0)
                add(
                    FusionTile(
                        readX = rx,
                        readY = ry,
                        readWidth = minOf(x0 + outW + margin, width) - rx,
                        readHeight = minOf(y0 + outH + margin, height) - ry,
                        outX = x0,
                        outY = y0,
                        outWidth = outW,
                        outHeight = outH,
                        fadeLeft = x0 > 0,
                        fadeTop = y0 > 0
                    )
                )
                x0 += step
            }
            y0 += step
        }
    }

    /** Weight of [tile]'s value at absolute (x, y) against what earlier tiles wrote there: a linear cross-fade. */
    fun alpha(tile: FusionTile, x: Int, y: Int, overlap: Int = OVERLAP): Float {
        val ax = if (tile.fadeLeft && x < tile.outX + overlap) (x - tile.outX + 0.5f) / overlap else 1f
        val ay = if (tile.fadeTop && y < tile.outY + overlap) (y - tile.outY + 0.5f) / overlap else 1f
        return ax * ay
    }
}

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
    fun confidence(v: Float): Float = (1f - (2f * v - 1f).toDouble().pow(12).toFloat()).coerceAtLeast(0f)

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
