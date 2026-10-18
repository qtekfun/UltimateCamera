package com.qtekfun.ultimatecamera.core.gpu.hdr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** One float channel of [width] x [height], row-major. */
class Plane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(data.size == width * height) { "plane size" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    operator fun set(x: Int, y: Int, v: Float) {
        data[y * width + x] = v
    }
}

/** An RGB tile with values in 0..1 (gamma-encoded, as the camera delivers them). */
class RgbTile(val width: Int, val height: Int, val r: Plane = Plane(width, height), val g: Plane = Plane(width, height), val b: Plane = Plane(width, height)) {
    val channels: List<Plane> get() = listOf(r, g, b)
}

/**
 * Exposure fusion (Mertens, Kautz and Van Reeth, 2007; D64): each exposure gets a per-pixel weight from contrast
 * (Laplacian of the grey image), saturation (standard deviation of R, G, B) and well-exposedness (Gaussian around
 * mid-grey per channel); the weights are normalised over the exposures and the images are blended per level of a
 * Laplacian pyramid with the weights' Gaussian pyramid, so the seams of the weight maps do not show. Pure Kotlin on
 * tiles, so memory stays bounded by the tile size (see [FusionTiling]).
 */
object ExposureFusion {
    /** Well-exposedness sigma around 0.5, as in the paper. */
    const val SIGMA = 0.2f
    internal const val EPSILON = 1e-12f

    /** Coarsest pyramid level side: below this the blur kernel sees mostly the border. */
    const val MIN_LEVEL_SIDE = 8

    /** Unnormalised Mertens weight of every pixel of [tile] (contrast x saturation x well-exposedness); rows on [pool]. */
    fun weights(tile: RgbTile, pool: ExecutorService? = null): Plane {
        val w = tile.width
        val h = tile.height
        val rd = tile.r.data
        val gd = tile.g.data
        val bd = tile.b.data
        val grey = FloatArray(w * h)
        inRowBands(h, pool) { y0, y1 -> for (i in y0 * w until y1 * w) grey[i] = (rd[i] + gd[i] + bd[i]) / 3f }
        val out = Plane(w, h)
        val o = out.data
        inRowBands(h, pool) { y0, y1 -> weightRows(w, h, y0, y1, grey, tile, o) }
        return out
    }

    fun wellExposed(v: Float): Float = exp(-((v - 0.5f) * (v - 0.5f)) / (2f * SIGMA * SIGMA))

    /** Normalises [weights] in place so they sum to 1 at every pixel (rows on [pool]). */
    fun normalise(weights: List<Plane>, pool: ExecutorService? = null) {
        val planes = weights.map { it.data }.toTypedArray()
        val k = planes.size
        val w = weights[0].width
        val even = 1f / k
        inRowBands(weights[0].height, pool) { y0, y1 ->
            for (i in y0 * w until y1 * w) {
                var sum = 0f
                for (j in 0 until k) sum += planes[j][i]
                if (sum <= 0f) {
                    for (j in 0 until k) planes[j][i] = even
                } else {
                    for (j in 0 until k) planes[j][i] /= sum
                }
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

    /**
     * Blurs with the 5-tap binomial kernel [1 4 6 4 1]/16 (edges clamped) and keeps every other pixel. Only the kept
     * pixels are computed (the horizontal pass on even columns, the vertical one on even rows), with the same sums.
     */
    fun reduce(p: Plane, pool: ExecutorService? = null): Plane {
        val w = p.width
        val h = p.height
        val ow = (w + 1) / 2
        val oh = (h + 1) / 2
        val d = p.data
        val tmp = FloatArray(ow * h)
        inRowBands(h, pool) { from, to -> reduceRows(d, w, ow, from, to, tmp) }
        val out = Plane(ow, oh)
        val o = out.data
        inRowBands(oh, pool) { from, to -> reduceColumns(tmp, h, ow, from, to, o) }
        return out
    }

    /** Upsamples [p] to [width] x [height] bilinearly (pixel centres aligned). */
    fun expand(p: Plane, width: Int, height: Int, pool: ExecutorService? = null): Plane {
        val out = Plane(width, height)
        val pw = p.width
        val d = p.data
        val o = out.data
        val sx = p.width.toFloat() / width
        val sy = p.height.toFloat() / height
        val x0s = IntArray(width)
        val x1s = IntArray(width)
        val txs = FloatArray(width)
        for (x in 0 until width) {
            val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (p.width - 1).toFloat())
            x0s[x] = fx.toInt()
            x1s[x] = minOf(x0s[x] + 1, p.width - 1)
            txs[x] = fx - x0s[x]
        }
        inRowBands(height, pool) { from, to ->
            for (y in from until to) {
                val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (p.height - 1).toFloat())
                val y0 = fy.toInt()
                val r0 = y0 * pw
                val r1 = minOf(y0 + 1, p.height - 1) * pw
                val ty = fy - y0
                val row = y * width
                for (x in 0 until width) {
                    val tx = txs[x]
                    val top = d[r0 + x0s[x]] * (1 - tx) + d[r0 + x1s[x]] * tx
                    val bottom = d[r1 + x0s[x]] * (1 - tx) + d[r1 + x1s[x]] * tx
                    o[row + x] = top * (1 - ty) + bottom * ty
                }
            }
        }
        return out
    }

    fun gaussianPyramid(p: Plane, levels: Int, pool: ExecutorService? = null): List<Plane> {
        val out = ArrayList<Plane>(levels)
        out += p
        repeat(levels - 1) { out += reduce(out.last(), pool) }
        return out
    }

    /** Band-pass levels plus the coarsest Gaussian level last; [collapse] restores [p] exactly. */
    fun laplacianPyramid(p: Plane, levels: Int, pool: ExecutorService? = null): List<Plane> {
        val gauss = gaussianPyramid(p, levels, pool)
        return gauss.mapIndexed { i, g ->
            if (i == gauss.lastIndex) {
                g
            } else {
                val up = expand(gauss[i + 1], g.width, g.height, pool).data
                val band = FloatArray(g.data.size)
                val gd = g.data
                inRowBands(g.height, pool) { y0, y1 -> for (j in y0 * g.width until y1 * g.width) band[j] = gd[j] - up[j] }
                Plane(g.width, g.height, band)
            }
        }
    }

    fun collapse(pyramid: List<Plane>, pool: ExecutorService? = null): Plane {
        var acc = pyramid.last()
        for (i in pyramid.lastIndex - 1 downTo 0) {
            val band = pyramid[i]
            val up = expand(acc, band.width, band.height, pool).data
            val sum = FloatArray(band.data.size)
            val bd = band.data
            inRowBands(band.height, pool) { y0, y1 -> for (j in y0 * band.width until y1 * band.width) sum[j] = up[j] + bd[j] }
            acc = Plane(band.width, band.height, sum)
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
 * [fuse] with the work spread over [pool] in a few long jobs (D90: on a phone, long jobs let the cores ramp up; many
 * short ones were slower): the weights in row bands, then the weights' Gaussian pyramids and the Laplacian pyramid of
 * every channel of every exposure together, then one job per colour channel adding them up in exposure order. Same
 * arithmetic in the same order per pixel, so the result equals [fuse] exactly.
 */
fun ExposureFusion.fuse(exposures: List<RgbTile>, maxLevels: Int, pool: ExecutorService): RgbTile {
    require(exposures.isNotEmpty()) { "no exposures" }
    val weights = weights(exposures, pool)
    normalise(weights, pool)
    return fuseWeighted(exposures, weights, maxLevels, pool)
}

/**
 * Standard deviation of [r], [g], [b] from their pairwise differences (the same value as around their mean): exactly
 * 0 for a grey pixel (D91). Around a float mean, `(r + g + b) / 3` rounds away from r for most greys, so grey areas
 * got a weight from rounding noise (about 1e-8 times the contrast, far above [ExposureFusion.EPSILON]) and the frame they took
 * depended on the last bit of the small images: the GPU's and the CPU's fusions then disagreed by 20 levels there.
 */
fun ExposureFusion.saturation(r: Float, g: Float, b: Float): Float {
    val rg = r - g
    val gb = g - b
    val br = b - r
    return sqrt((rg * rg + gb * gb + br * br) / 9f)
}

/** [ExposureFusion.weights] of every exposure, each in [WEIGHT_BANDS] row bands, all in one batch on [pool]. */
fun ExposureFusion.weights(exposures: List<RgbTile>, pool: ExecutorService): List<Plane> {
    val out = exposures.map { Plane(it.width, it.height) }
    val jobs = exposures.indices.flatMap { k ->
        val h = exposures[k].height
        (0 until WEIGHT_BANDS).map { band -> Callable { weightBand(exposures[k], h * band / WEIGHT_BANDS, h * (band + 1) / WEIGHT_BANDS, out[k].data) } }
    }
    pool.invokeAll(jobs).forEach { it.get() }
    return out
}

private const val WEIGHT_BANDS = 3

/** [fuse] with the exposures' [normalised] weights already computed (as [ExposureFusion.weights] then [normalise]). */
fun ExposureFusion.fuseWeighted(exposures: List<RgbTile>, normalised: List<Plane>, maxLevels: Int, pool: ExecutorService): RgbTile {
    require(exposures.isNotEmpty() && exposures.size == normalised.size) { "exposures and weights" }
    val w = exposures.first().width
    val h = exposures.first().height
    val levels = levels(w, h, maxLevels)
    val n = exposures.size
    val pyramids = pool.invokeAll(
        normalised.map { p -> Callable { gaussianPyramid(p, levels) } } +
            exposures.flatMap { tile -> tile.channels.map { ch -> Callable { laplacianPyramid(ch, levels) } } }
    )
    val channels = pool.invokeAll(
        (0 until 3).map { c ->
            Callable {
                var acc: List<Plane>? = null
                for (k in 0 until n) {
                    val band = pyramids[n + k * 3 + c].get()
                    val current = acc ?: band.map { Plane(it.width, it.height) }.also { acc = it }
                    val weight = pyramids[k].get()
                    for (l in 0 until levels) {
                        val target = current[l].data
                        val b = band[l].data
                        val wl = weight[l].data
                        for (i in target.indices) target[i] += wl[i] * b[i]
                    }
                }
                collapse(requireNotNull(acc)).also { p -> for (i in p.data.indices) p.data[i] = p.data[i].coerceIn(0f, 1f) }
            }
        }
    ).map { it.get() }
    return RgbTile(w, h, channels[0], channels[1], channels[2])
}

/** Weight rows [y0] until [y1] of [tile] into [out], from the grey rows around them (as [weights] computes them). */
internal fun weightBand(tile: RgbTile, y0: Int, y1: Int, out: FloatArray) {
    val w = tile.width
    val h = tile.height
    val rd = tile.r.data
    val gd = tile.g.data
    val bd = tile.b.data
    val grey = FloatArray(w * h)
    for (i in maxOf(y0 - 1, 0) * w until minOf(y1 + 1, h) * w) grey[i] = (rd[i] + gd[i] + bd[i]) / 3f
    weightRows(w, h, y0, y1, grey, tile, out)
}

@Suppress("LongParameterList")
private fun weightRows(w: Int, h: Int, y0: Int, y1: Int, grey: FloatArray, tile: RgbTile, o: FloatArray) {
    val rd = tile.r.data
    val gd = tile.g.data
    val bd = tile.b.data
    for (y in y0 until y1) {
        val row = y * w
        val up = maxOf(y - 1, 0) * w
        val down = minOf(y + 1, h - 1) * w
        for (x in 0 until w) {
            val i = row + x
            val c = grey[i]
            val lap = grey[row + maxOf(x - 1, 0)] + grey[row + minOf(x + 1, w - 1)] + grey[up + x] + grey[down + x] - 4f * c
            val contrast = abs(lap)
            val r = rd[i]
            val g = gd[i]
            val b = bd[i]
            val exposed = ExposureFusion.wellExposed(r) * ExposureFusion.wellExposed(g) * ExposureFusion.wellExposed(b)
            o[i] = contrast * ExposureFusion.saturation(r, g, b) * exposed + ExposureFusion.EPSILON
        }
    }
}

/**
 * Runs [block] over rows 0 until [n] in bands on [pool] (inline when there is no pool or too few rows). Every
 * row's arithmetic is the same either way, so results do not depend on the banding (D90).
 */
internal inline fun inRowBands(n: Int, pool: ExecutorService?, crossinline block: (Int, Int) -> Unit) {
    val bands = if (pool == null) 1 else minOf(FUSION_BANDS, n / MIN_BAND_ROWS)
    if (bands <= 1) {
        block(0, n)
        return
    }
    requireNotNull(pool).invokeAll((0 until bands).map { i -> Callable { block(n * i / bands, n * (i + 1) / bands) } }).forEach { it.get() }
}

internal const val FUSION_BANDS = 16
internal const val MIN_BAND_ROWS = 8

@Suppress("LongParameterList")
private fun reduceRows(d: FloatArray, w: Int, ow: Int, from: Int, to: Int, tmp: FloatArray) {
    for (y in from until to) {
        val row = y * w
        for (ox in 0 until ow) {
            val x = ox * 2
            tmp[y * ow + ox] = (
                d[row + maxOf(x - 2, 0)] + 4 * d[row + maxOf(x - 1, 0)] + 6 * d[row + x] +
                    4 * d[row + minOf(x + 1, w - 1)] + d[row + minOf(x + 2, w - 1)]
                ) / 16f
        }
    }
}

@Suppress("LongParameterList")
private fun reduceColumns(tmp: FloatArray, h: Int, ow: Int, from: Int, to: Int, o: FloatArray) {
    for (oy in from until to) {
        val y = oy * 2
        val m2 = maxOf(y - 2, 0) * ow
        val m1 = maxOf(y - 1, 0) * ow
        val c = y * ow
        val p1 = minOf(y + 1, h - 1) * ow
        val p2 = minOf(y + 2, h - 1) * ow
        for (ox in 0 until ow) {
            o[oy * ow + ox] = (tmp[m2 + ox] + 4 * tmp[m1 + ox] + 6 * tmp[c + ox] + 4 * tmp[p1 + ox] + tmp[p2 + ox]) / 16f
        }
    }
}
