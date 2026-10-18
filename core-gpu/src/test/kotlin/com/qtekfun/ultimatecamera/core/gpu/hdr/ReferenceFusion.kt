package com.qtekfun.ultimatecamera.core.gpu.hdr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

// The fusion and alignment code as it was before D90's rewrite for speed (verbatim but for the names and D91's
// saturation): the reference the faster code must match to the bit (ReferenceEquivalenceTest).

object ReferenceFusion {
    /** Well-exposedness sigma around 0.5, as in the paper. */
    const val SIGMA = 0.2f
    private const val EPSILON = 1e-12f

    /** Coarsest pyramid level side: below this the blur kernel sees mostly the border. */
    const val MIN_LEVEL_SIDE = 8

    /** Unnormalised Mertens weight of every pixel of [tile] (contrast x saturation x well-exposedness). */
    fun weights(tile: RgbTile): Plane {
        val w = tile.width
        val h = tile.height
        val grey = Plane(w, h)
        for (i in grey.data.indices) grey.data[i] = (tile.r.data[i] + tile.g.data[i] + tile.b.data[i]) / 3f
        val out = Plane(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = grey[x, y]
                val lap = grey[(x - 1).coerceAtLeast(0), y] + grey[(x + 1).coerceAtMost(w - 1), y] +
                    grey[x, (y - 1).coerceAtLeast(0)] + grey[x, (y + 1).coerceAtMost(h - 1)] - 4f * c
                val contrast = abs(lap)
                val r = tile.r.data[i]
                val g = tile.g.data[i]
                val b = tile.b.data[i]
                // D91: the standard deviation from pairwise differences (exactly 0 for grey), as the production code.
                val saturation = sqrt(((r - g) * (r - g) + (g - b) * (g - b) + (b - r) * (b - r)) / 9f)
                val exposed = wellExposed(r) * wellExposed(g) * wellExposed(b)
                out.data[i] = contrast * saturation * exposed + EPSILON
            }
        }
        return out
    }

    fun wellExposed(v: Float): Float = exp(-((v - 0.5f) * (v - 0.5f)) / (2f * SIGMA * SIGMA))

    /** Normalises [weights] in place so they sum to 1 at every pixel. */
    fun normalise(weights: List<Plane>) {
        val n = weights.first().data.size
        for (i in 0 until n) {
            var sum = 0f
            for (p in weights) sum += p.data[i]
            if (sum <= 0f) {
                for (p in weights) p.data[i] = 1f / weights.size
            } else {
                for (p in weights) p.data[i] /= sum
            }
        }
    }

    /** Pyramid levels for a [width] x [height] tile, at most [maxLevels], coarsest side at least [MIN_LEVEL_SIDE]. */
    fun levels(width: Int, height: Int, maxLevels: Int): Int {
        var side = minOf(width, height)
        var n = 1
        while (n < maxLevels && (side + 1) / 2 >= MIN_LEVEL_SIDE) {
            side = (side + 1) / 2
            n++
        }
        return n
    }

    /** Blurs with the 5-tap binomial kernel [1 4 6 4 1]/16 (edges clamped) and keeps every other pixel. */
    fun reduce(p: Plane): Plane {
        val blurred = referenceBlur(p)
        val w = (p.width + 1) / 2
        val h = (p.height + 1) / 2
        val out = Plane(w, h)
        for (y in 0 until h) for (x in 0 until w) out[x, y] = blurred[x * 2, y * 2]
        return out
    }

    /** Upsamples [p] to [width] x [height] bilinearly (pixel centres aligned). */
    fun expand(p: Plane, width: Int, height: Int): Plane {
        val out = Plane(width, height)
        val sx = p.width.toFloat() / width
        val sy = p.height.toFloat() / height
        for (y in 0 until height) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (p.height - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = (y0 + 1).coerceAtMost(p.height - 1)
            val ty = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (p.width - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = (x0 + 1).coerceAtMost(p.width - 1)
                val tx = fx - x0
                val top = p[x0, y0] * (1 - tx) + p[x1, y0] * tx
                val bottom = p[x0, y1] * (1 - tx) + p[x1, y1] * tx
                out[x, y] = top * (1 - ty) + bottom * ty
            }
        }
        return out
    }

    fun gaussianPyramid(p: Plane, levels: Int): List<Plane> {
        val out = ArrayList<Plane>(levels)
        out += p
        repeat(levels - 1) { out += reduce(out.last()) }
        return out
    }

    /** Band-pass levels plus the coarsest Gaussian level last; [collapse] restores [p] exactly. */
    fun laplacianPyramid(p: Plane, levels: Int): List<Plane> {
        val gauss = gaussianPyramid(p, levels)
        return gauss.mapIndexed { i, g ->
            if (i == gauss.lastIndex) {
                g
            } else {
                val up = expand(gauss[i + 1], g.width, g.height)
                Plane(g.width, g.height, FloatArray(g.data.size) { g.data[it] - up.data[it] })
            }
        }
    }

    fun collapse(pyramid: List<Plane>): Plane {
        var acc = pyramid.last()
        for (i in pyramid.lastIndex - 1 downTo 0) {
            val band = pyramid[i]
            val up = expand(acc, band.width, band.height)
            acc = Plane(band.width, band.height, FloatArray(band.data.size) { up.data[it] + band.data[it] })
        }
        return acc
    }

    /**
     * Fuses same-sized [exposures] into one tile with values clamped to 0..1. Builds one exposure's pyramids at a time,
     * so the peak memory is the inputs plus about three tiles.
     */
    fun fuse(exposures: List<RgbTile>, maxLevels: Int = DEFAULT_LEVELS): RgbTile {
        require(exposures.isNotEmpty()) { "no exposures" }
        val w = exposures.first().width
        val h = exposures.first().height
        val weights = exposures.map(::weights)
        normalise(weights)
        val levels = levels(w, h, maxLevels)
        var acc: List<MutableList<Plane>>? = null
        exposures.forEachIndexed { k, tile ->
            val gw = gaussianPyramid(weights[k], levels)
            val bands = tile.channels.map { ch -> laplacianPyramid(ch, levels) }
            val current = acc ?: bands.map { lp -> lp.map { Plane(it.width, it.height) }.toMutableList() }.also { acc = it }
            for (c in 0 until 3) {
                for (l in 0 until levels) {
                    val target = current[c][l].data
                    val band = bands[c][l].data
                    val weight = gw[l].data
                    for (i in target.indices) target[i] += weight[i] * band[i]
                }
            }
        }
        val fused = requireNotNull(acc).map { collapse(it) }
        fused.forEach { p -> for (i in p.data.indices) p.data[i] = p.data[i].coerceIn(0f, 1f) }
        return RgbTile(w, h, fused[0], fused[1], fused[2])
    }

    const val DEFAULT_LEVELS = 6
}

/**
 * [fuse] with the work spread over [pool]: weights and their pyramids per exposure, then one job per colour channel.
 * Same arithmetic in the same order per pixel, so the result equals [fuse] exactly.
 */
fun ReferenceFusion.fuse(exposures: List<RgbTile>, maxLevels: Int, pool: ExecutorService): RgbTile {
    require(exposures.isNotEmpty()) { "no exposures" }
    val w = exposures.first().width
    val h = exposures.first().height
    val weights = pool.invokeAll(exposures.map { e -> Callable { weights(e) } }).map { it.get() }
    normalise(weights)
    val levels = levels(w, h, maxLevels)
    val gw = pool.invokeAll(weights.map { p -> Callable { gaussianPyramid(p, levels) } }).map { it.get() }
    val channels = pool.invokeAll(
        (0 until 3).map { c ->
            Callable {
                var acc: List<Plane>? = null
                exposures.forEachIndexed { k, tile ->
                    val bands = laplacianPyramid(tile.channels[c], levels)
                    val current = acc ?: bands.map { Plane(it.width, it.height) }.also { acc = it }
                    for (l in 0 until levels) {
                        val target = current[l].data
                        val band = bands[l].data
                        val weight = gw[k][l].data
                        for (i in target.indices) target[i] += weight[i] * band[i]
                    }
                }
                collapse(requireNotNull(acc)).also { p -> for (i in p.data.indices) p.data[i] = p.data[i].coerceIn(0f, 1f) }
            }
        }
    ).map { it.get() }
    return RgbTile(w, h, channels[0], channels[1], channels[2])
}

/** The 5-tap binomial blur [1 4 6 4 1]/16, separable, edges clamped. */
private fun referenceBlur(p: Plane): Plane {
    val w = p.width
    val h = p.height
    val tmp = Plane(w, h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            tmp[x, y] = (
                p[(x - 2).coerceAtLeast(0), y] + 4 * p[(x - 1).coerceAtLeast(0), y] + 6 * p[x, y] +
                    4 * p[(x + 1).coerceAtMost(w - 1), y] + p[(x + 2).coerceAtMost(w - 1), y]
                ) / 16f
        }
    }
    val out = Plane(w, h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            out[x, y] = (
                tmp[x, (y - 2).coerceAtLeast(0)] + 4 * tmp[x, (y - 1).coerceAtLeast(0)] + 6 * tmp[x, y] +
                    4 * tmp[x, (y + 1).coerceAtMost(h - 1)] + tmp[x, (y + 2).coerceAtMost(h - 1)]
                ) / 16f
        }
    }
    return out
}

/** Reference: [ExposureAlignment] before D90. */
object ReferenceAlignment {
    private const val NOISE_BAND = 4f / 255f

    fun shift(reference: Plane, moving: Plane, radius: Int): Pair<Int, Int> = shiftAround(reference, moving, 0, 0, radius)

    /**
     * Coarse to fine: the shift found on planes halved until their short side is under [COARSE_SIDE] (radius halved
     * too), refined by ±1 at each finer level. Same answer as [shift] for real scenes at a fraction of the cost (the
     * full search at 500 × 375 took over a second per frame on the Pixel 8).
     */
    fun shiftPyramid(reference: Plane, moving: Plane, radius: Int): Pair<Int, Int> {
        if (minOf(reference.width, reference.height) < COARSE_SIDE * 2 || radius <= 2) return shift(reference, moving, radius)
        val (cx, cy) = shiftPyramid(halve(reference), halve(moving), (radius + 1) / 2)
        return shiftAround(reference, moving, cx * 2, cy * 2, 1)
    }

    /** [p] averaged over 2 × 2 blocks. */
    fun halve(p: Plane): Plane {
        val w = p.width / 2
        val h = p.height / 2
        val out = Plane(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[x, y] = (p[2 * x, 2 * y] + p[2 * x + 1, 2 * y] + p[2 * x, 2 * y + 1] + p[2 * x + 1, 2 * y + 1]) * 0.25f
            }
        }
        return out
    }

    private const val COARSE_SIDE = 48

    private fun shiftAround(reference: Plane, moving: Plane, cx: Int, cy: Int, radius: Int): Pair<Int, Int> {
        val refMedian = median(reference)
        val movMedian = median(moving)
        var best = cx to cy
        var bestScore = Long.MAX_VALUE
        for (dy in cy - radius..cy + radius) {
            for (dx in cx - radius..cx + radius) {
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
