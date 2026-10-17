package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import kotlin.math.exp
import kotlin.math.floor

/**
 * Edge-aware upsampling of a low-resolution depth map guided by the colour image (joint bilateral upsampling, Kopf et
 * al. 2007; D73). Every output pixel averages the low-resolution depth samples around it, weighted by distance (a
 * Gaussian in low-resolution pixels) and by how alike the guide is (a Gaussian on the difference between the output
 * pixel's guide value and the guide at the depth sample, [guideLow]: the guide reduced to the depth's size). Depth
 * therefore stops at the colour edges instead of bleeding across them. Samples without depth weigh nothing.
 */
class GuidedUpsample(private val depth: Plane, private val guideLow: Plane, private val spatialSigma: Float = 1f, private val rangeSigma: Float = 0.08f) {
    init {
        require(depth.width == guideLow.width && depth.height == guideLow.height) { "guide and depth sizes differ" }
    }

    private val spatial = 1f / (2f * spatialSigma * spatialSigma)
    private val range = 1f / (2f * rangeSigma * rangeSigma)

    /**
     * Depth at the normalised position ([nx], [ny] in 0..1 over the whole image, pixel centres) whose guide value is
     * [guide]; [Bokeh.INVALID] when no sample around it has depth.
     */
    fun at(nx: Float, ny: Float, guide: Float): Float {
        val lx = nx * depth.width - 0.5f
        val ly = ny * depth.height - 0.5f
        val x0 = floor(lx).toInt()
        val y0 = floor(ly).toInt()
        var sum = 0f
        var weights = 0f
        for (y in (y0 - RADIUS + 1).coerceAtLeast(0)..(y0 + RADIUS).coerceAtMost(depth.height - 1)) {
            for (x in (x0 - RADIUS + 1).coerceAtLeast(0)..(x0 + RADIUS).coerceAtMost(depth.width - 1)) {
                val d = depth[x, y]
                val dx = x - lx
                val dy = y - ly
                val dg = guide - guideLow[x, y]
                // Samples without depth weigh nothing.
                val w = if (d <= Bokeh.INVALID) 0f else exp(-(dx * dx + dy * dy) * spatial - dg * dg * range)
                sum += w * d
                weights += w
            }
        }
        return if (weights > MIN_WEIGHT) sum / weights else Bokeh.INVALID
    }

    /** The whole map at the size of [guide] (tests, the depth PNG). */
    fun upsample(guide: Plane): Plane {
        val out = Plane(guide.width, guide.height)
        for (y in 0 until guide.height) {
            val ny = (y + 0.5f) / guide.height
            for (x in 0 until guide.width) out[x, y] = at((x + 0.5f) / guide.width, ny, guide[x, y])
        }
        return out
    }

    private companion object {
        /** Low-resolution samples on each side of the output pixel (a 4 × 4 neighbourhood). */
        const val RADIUS = 2
        const val MIN_WEIGHT = 1e-6f
    }
}

/** Box-averages this plane down to [width] x [height] (a guide for [GuidedUpsample], the low-resolution blur input). */
fun Plane.reduced(width: Int, height: Int): Plane {
    val out = Plane(width, height)
    for (y in 0 until height) {
        val sy0 = y * this.height / height
        val sy1 = ((y + 1) * this.height / height).coerceAtLeast(sy0 + 1)
        for (x in 0 until width) {
            val sx0 = x * this.width / width
            val sx1 = ((x + 1) * this.width / width).coerceAtLeast(sx0 + 1)
            out[x, y] = boxAverage(sx0 until sx1, sy0 until sy1)
        }
    }
    return out
}

private fun Plane.boxAverage(xs: IntRange, ys: IntRange): Float {
    var sum = 0f
    for (sy in ys) for (sx in xs) sum += this[sx, sy]
    return sum / (xs.count() * ys.count())
}
