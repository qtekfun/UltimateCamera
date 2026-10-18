package com.qtekfun.ultimatecamera.core.gpu.hdr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
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

    fun shift(reference: Plane, moving: Plane, radius: Int, pool: ExecutorService? = null): Pair<Int, Int> = shiftAround(reference, moving, 0, 0, radius, pool)

    /**
     * Coarse to fine: the shift found on planes halved until their short side is under [COARSE_SIDE] (radius halved
     * too), refined by ±1 at each finer level. Same answer as [shift] for real scenes at a fraction of the cost (the
     * full search at 500 × 375 took over a second per frame on the Pixel 8). With [pool] the candidate shifts of each
     * level are scored on its threads (D90), with the same answer.
     */
    fun shiftPyramid(reference: Plane, moving: Plane, radius: Int, pool: ExecutorService? = null): Pair<Int, Int> {
        if (minOf(reference.width, reference.height) < COARSE_SIDE * 2 || radius <= 2) return shift(reference, moving, radius, pool)
        val (cx, cy) = shiftPyramid(halve(reference), halve(moving), (radius + 1) / 2, pool)
        return shiftAround(reference, moving, cx * 2, cy * 2, 1, pool)
    }

    /** [p] averaged over 2 × 2 blocks. */
    fun halve(p: Plane): Plane {
        val w = p.width / 2
        val h = p.height / 2
        val out = Plane(w, h)
        val d = p.data
        val pw = p.width
        for (y in 0 until h) {
            val r0 = 2 * y * pw
            val r1 = r0 + pw
            for (x in 0 until w) {
                out.data[y * w + x] = (d[r0 + 2 * x] + d[r0 + 2 * x + 1] + d[r1 + 2 * x] + d[r1 + 2 * x + 1]) * 0.25f
            }
        }
        return out
    }

    private const val COARSE_SIDE = 48

    /**
     * A plane thresholded at its median, one byte per pixel: bit 0 = above the median, bit 1 = far enough from it to be
     * compared ([NOISE_BAND]).
     */
    private class Bitmaps(p: Plane) {
        val width = p.width
        val height = p.height
        val bits = ByteArray(p.data.size)

        init {
            val median = median(p)
            val d = p.data
            for (i in d.indices) {
                val v = d[i]
                bits[i] = ((if (v > median) ABOVE else 0) or (if (abs(v - median) >= NOISE_BAND) COMPARED else 0)).toByte()
            }
        }
    }

    private const val ABOVE = 1
    private const val COMPARED = 2

    /** Row bands each candidate's count is split into, so the work spreads evenly over big and little cores. */
    private const val BANDS = 2

    @Suppress("LongParameterList")
    private fun shiftAround(reference: Plane, moving: Plane, cx: Int, cy: Int, radius: Int, pool: ExecutorService?): Pair<Int, Int> {
        val (ref, mov) = if (pool == null) {
            Bitmaps(reference) to Bitmaps(moving)
        } else {
            pool.invokeAll(listOf(Callable { Bitmaps(reference) }, Callable { Bitmaps(moving) })).map { it.get() }.let { it[0] to it[1] }
        }
        val candidates = (cy - radius..cy + radius).flatMap { dy -> (cx - radius..cx + radius).map { dx -> dx to dy } }
        val scores = if (pool == null) {
            candidates.map { (dx, dy) -> score(differences(ref, mov, dx, dy, 0, 1)) }
        } else {
            val parts = pool.invokeAll(
                candidates.flatMap { (dx, dy) ->
                    (0 until BANDS).map { band -> Callable { differences(ref, mov, dx, dy, band, BANDS) } }
                }
            )
            candidates.indices.map { c ->
                var diff = 0L
                var count = 0L
                for (band in 0 until BANDS) {
                    val (d, n) = parts[c * BANDS + band].get()
                    diff += d
                    count += n
                }
                score(diff to count)
            }
        }
        var best = cx to cy
        var bestScore = Long.MAX_VALUE
        for ((i, candidate) in candidates.withIndex()) {
            val score = scores[i]
            val (dx, dy) = candidate
            if (score < bestScore || (score == bestScore && abs(dx) + abs(dy) < abs(best.first) + abs(best.second))) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    /** Differing bits per 10 000 compared pixels (no compared pixel: never chosen). */
    private fun score(counts: Pair<Long, Long>): Long = if (counts.second == 0L) Long.MAX_VALUE else counts.first * SCORE_SCALE / counts.second

    /**
     * Differing and compared pixels when [mov] is read at (x + dx, y + dy), over band [band] of [bands] of the
     * overlapping rows.
     */
    @Suppress("LongParameterList")
    private fun differences(ref: Bitmaps, mov: Bitmaps, dx: Int, dy: Int, band: Int, bands: Int): Pair<Long, Long> {
        var diff = 0L
        var count = 0L
        val x0 = maxOf(0, -dx)
        val x1 = minOf(ref.width, mov.width - dx)
        val y0 = maxOf(0, -dy)
        val y1 = minOf(ref.height, mov.height - dy)
        val rows = maxOf(0, y1 - y0)
        val a = ref.bits
        val b = mov.bits
        for (y in y0 + rows * band / bands until y0 + rows * (band + 1) / bands) {
            val r = y * ref.width
            val m = (y + dy) * mov.width + dx
            for (x in x0 until x1) {
                val p = a[r + x].toInt()
                val q = b[m + x].toInt()
                if (p and q and COMPARED != 0) {
                    count++
                    diff += (p xor q) and ABOVE
                }
            }
        }
        return diff to count
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
