package com.qtekfun.ultimatecamera.core.gpu.hdr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
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
    const val SIZE = 4096
    private val TABLE = FloatArray(SIZE + 1) { i -> log2Of(Srgb.decode(i.toFloat() / SIZE)) }

    /** A copy of the table (SIZE + 1 entries), for the GPU rendering (D90). */
    fun table(): FloatArray = TABLE.copyOf()

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
        /** With [pool] the box sums run on its threads (rows, then columns); the result is the same to the bit. */
        fun of(low: Plane, radius: Int, eps: Float, pool: ExecutorService? = null): GuidedBase {
            val mean = boxMean(low, radius, pool)
            val l = low.data
            val sq = Plane(low.width, low.height)
            for (i in l.indices) sq.data[i] = l[i] * l[i]
            val meanSq = boxMean(sq, radius, pool).data
            val m = mean.data
            val a = Plane(low.width, low.height)
            val b = Plane(low.width, low.height)
            for (i in l.indices) {
                val variance = maxOf(meanSq[i] - m[i] * m[i], 0f)
                val ai = variance / (variance + eps)
                a.data[i] = ai
                b.data[i] = m[i] - ai * m[i]
            }
            return GuidedBase(boxMean(a, radius, pool), boxMean(b, radius, pool))
        }

        /** Mean over the (2r+1)² window clipped to the plane, by running sums (O(1) per pixel). */
        fun boxMean(p: Plane, radius: Int, pool: ExecutorService? = null): Plane {
            val w = p.width
            val h = p.height
            val tmp = Plane(w, h)
            chunks(h, pool) { from, to ->
                val row = DoubleArray(w + 1)
                val d = p.data
                val t = tmp.data
                for (y in from until to) {
                    val o = y * w
                    for (x in 0 until w) row[x + 1] = row[x] + d[o + x]
                    for (x in 0 until w) {
                        val x0 = maxOf(x - radius, 0)
                        val x1 = minOf(x + radius + 1, w)
                        t[o + x] = ((row[x1] - row[x0]) / (x1 - x0)).toFloat()
                    }
                }
            }
            val out = Plane(w, h)
            chunks(w, pool) { from, to ->
                val col = DoubleArray(h + 1)
                val t = tmp.data
                val d = out.data
                for (x in from until to) {
                    for (y in 0 until h) col[y + 1] = col[y] + t[y * w + x]
                    for (y in 0 until h) {
                        val y0 = maxOf(y - radius, 0)
                        val y1 = minOf(y + radius + 1, h)
                        d[y * w + x] = ((col[y1] - col[y0]) / (y1 - y0)).toFloat()
                    }
                }
            }
            return out
        }

        /** Runs [block] over 0 until [n] in contiguous chunks, on [pool] when given. */
        private inline fun chunks(n: Int, pool: ExecutorService?, crossinline block: (Int, Int) -> Unit) {
            if (pool == null || n < 2) {
                block(0, n)
                return
            }
            val step = (n + CHUNKS - 1) / CHUNKS
            pool.invokeAll((0 until n step step).map { from -> Callable { block(from, minOf(from + step, n)) } }).forEach { it.get() }
        }

        private const val CHUNKS = 16
    }
}
