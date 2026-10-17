package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.log2
import kotlin.math.pow

/** sRGB transfer functions (display value ↔ linear light), both in 0..1. */
object Srgb {
    fun decode(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun encode(l: Float): Float = if (l <= 0.0031308f) l * 12.92f else (1.055 * l.toDouble().pow(1 / 2.4) - 0.055).toFloat()
}

/**
 * Display value → log2 of linear light, tabulated (4096 entries, linear interpolation) so the full-size pass needs no
 * `pow` or `log` per pixel. Values below [LOG_FLOOR] (about display 0.003) are clamped to it.
 */
object LogLuma {
    const val LOG_FLOOR = -12f
    private const val SIZE = 4096
    private val TABLE = FloatArray(SIZE + 1) { i -> log2Of(Srgb.decode(i.toFloat() / SIZE)) }

    fun of(v: Float): Float {
        val f = v.coerceIn(0f, 1f) * SIZE
        val i = f.toInt().coerceAtMost(SIZE - 1)
        return TABLE[i] + (TABLE[i + 1] - TABLE[i]) * (f - i)
    }

    fun log2Of(x: Float): Float = if (x <= 0f) LOG_FLOOR else log2(x).coerceAtLeast(LOG_FLOOR)
}

/**
 * The edge-aware base layer of the local tone mapping: a self-guided filter (He, Sun, Tang) on the low-resolution log
 * luma. Per low pixel it fits `base = a·L + b` over a (2r+1)² window; [a] → 1 across strong edges (the base follows the
 * edge, so no halo) and → 0 in flat or finely textured areas (the base is the local mean, so texture stays as detail).
 * The full-size pass evaluates `a·L + b` with the bilinearly upsampled [a], [b] and the full-size L as the guide
 * ("fast guided filter"), which keeps the edges sharp at full resolution.
 */
class GuidedBase(val a: Plane, val b: Plane) {
    companion object {
        fun of(low: Plane, radius: Int, eps: Float): GuidedBase {
            val mean = boxMean(low, radius)
            val sq = Plane(low.width, low.height, FloatArray(low.data.size) { low.data[it] * low.data[it] })
            val meanSq = boxMean(sq, radius)
            val a = Plane(low.width, low.height)
            val b = Plane(low.width, low.height)
            for (i in low.data.indices) {
                val variance = (meanSq.data[i] - mean.data[i] * mean.data[i]).coerceAtLeast(0f)
                a.data[i] = variance / (variance + eps)
                b.data[i] = mean.data[i] - a.data[i] * mean.data[i]
            }
            return GuidedBase(boxMean(a, radius), boxMean(b, radius))
        }

        /** Mean over the (2r+1)² window clipped to the plane, by running sums (O(1) per pixel). */
        fun boxMean(p: Plane, radius: Int): Plane {
            val w = p.width
            val h = p.height
            val tmp = Plane(w, h)
            val row = DoubleArray(w + 1)
            for (y in 0 until h) {
                for (x in 0 until w) row[x + 1] = row[x] + p.data[y * w + x]
                for (x in 0 until w) {
                    val x0 = (x - radius).coerceAtLeast(0)
                    val x1 = (x + radius + 1).coerceAtMost(w)
                    tmp.data[y * w + x] = ((row[x1] - row[x0]) / (x1 - x0)).toFloat()
                }
            }
            val out = Plane(w, h)
            val col = DoubleArray(h + 1)
            for (x in 0 until w) {
                for (y in 0 until h) col[y + 1] = col[y] + tmp.data[y * w + x]
                for (y in 0 until h) {
                    val y0 = (y - radius).coerceAtLeast(0)
                    val y1 = (y + radius + 1).coerceAtMost(h)
                    out.data[y * w + x] = ((col[y1] - col[y0]) / (y1 - y0)).toFloat()
                }
            }
            return out
        }
    }
}
