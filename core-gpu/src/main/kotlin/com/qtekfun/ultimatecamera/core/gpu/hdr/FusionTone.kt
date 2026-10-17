package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.sqrt

/**
 * Exposure and highlight tone of a fused photo (D64 follow-up). Mertens fusion of a −2/0/+2 EV bracket keeps the sky
 * but lands about 1.2 stops under the 0 EV frame and never reaches white (owner's shot: p50 78, p99 205 against the
 * phone's own camera at p50 152, p99 238). This monotone curve (Fritsch–Carlson cubic through four points) lifts the
 * fused median to the reference frame's median (kept within [MIN_TARGET]…[MAX_TARGET]) and the fused 99th percentile
 * to at least [HIGHLIGHT_TARGET], with a shoulder above it so the recovered sky is compressed, not clipped. It never
 * darkens: a fusion already brighter keeps its median.
 */
class FusionTone private constructor(private val lut: IntArray) {
    /** The toned 8-bit value of [v] (0..255). */
    operator fun get(v: Int): Int = lut[v]

    /** [argb] toned in place, channel by channel. */
    fun apply(argb: IntArray, offset: Int = 0, length: Int = argb.size) {
        for (i in offset until offset + length) {
            val p = argb[i]
            argb[i] = (p and ALPHA) or (lut[(p shr 16) and 0xFF] shl 16) or (lut[(p shr 8) and 0xFF] shl 8) or lut[p and 0xFF]
        }
    }

    val isIdentity: Boolean get() = lut.indices.all { lut[it] == it }

    companion object {
        const val MIN_TARGET = 0.45f
        const val MAX_TARGET = 0.62f
        const val HIGHLIGHT_TARGET = 0.94f
        private const val ALPHA = -0x1000000
        private const val LEVELS = 256

        /** The curve for a fused image with luma [fused] whose 0 EV frame has luma [reference] (both 0..1, any size). */
        fun of(fused: Plane, reference: Plane): FusionTone {
            val f = fused.data.copyOf().also { it.sort() }
            val r = reference.data.copyOf().also { it.sort() }
            return of(f[f.size / 2], f[(f.size * 0.99).toInt().coerceAtMost(f.size - 1)], r[r.size / 2])
        }

        /** The curve from the fused [median] and [p99] and the reference frame's [referenceMedian]. */
        fun of(median: Float, p99: Float, referenceMedian: Float): FusionTone {
            val m = median.coerceIn(0.01f, 0.9f)
            val top = p99.coerceIn(m + 0.02f, 0.98f)
            val targetMedian = maxOf(m, referenceMedian.coerceIn(MIN_TARGET, MAX_TARGET))
            val targetTop = maxOf(top, HIGHLIGHT_TARGET).coerceAtLeast(targetMedian + 0.02f).coerceAtMost(0.99f)
            val curve = MonotoneCurve(floatArrayOf(0f, m, top, 1f), floatArrayOf(0f, targetMedian, targetTop, 1f))
            return FusionTone(IntArray(LEVELS) { (curve(it / 255f) * 255f + 0.5f).toInt().coerceIn(0, 255) })
        }

        /** Luma plane (BT.601) of [t]. */
        fun luma(t: RgbTile): Plane = Plane(t.width, t.height, FloatArray(t.width * t.height) { PhotoLook.luma(t.r.data[it], t.g.data[it], t.b.data[it]) })
    }
}

/** Monotone cubic Hermite interpolation (Fritsch–Carlson) through increasing [xs] and non-decreasing [ys]. */
class MonotoneCurve(private val xs: FloatArray, private val ys: FloatArray) {
    private val m = FloatArray(xs.size)

    init {
        require(xs.size == ys.size && xs.size >= 2) { "points" }
        val n = xs.size
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] > 0f) (d[i - 1] + d[i]) / 2f else 0f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val h = sqrt(a * a + b * b)
                if (h > LIMIT) {
                    m[i] = LIMIT / h * a * d[i]
                    m[i + 1] = LIMIT / h * b * d[i]
                }
            }
        }
    }

    operator fun invoke(x: Float): Float {
        val i = (0 until xs.size - 1).firstOrNull { x <= xs[it + 1] } ?: return ys.last()
        val h = xs[i + 1] - xs[i]
        val t = ((x - xs[i]) / h).coerceIn(0f, 1f)
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
    }

    private companion object {
        const val LIMIT = 3f
    }
}
