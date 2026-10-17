package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.sqrt

/**
 * 8-bit BT.601 luma statistics of a photo, as the owner measured the OPPO comparison shots: mean, standard deviation,
 * 1st and 99th percentiles and the share of clipped pixels (≥ 254), in 0..255.
 */
data class LumaStats(val mean: Double, val sd: Double, val p1: Int, val p99: Int, val clipped: Double, val saturation: Double = 0.0) {
    override fun toString(): String = "mean %.1f sd %.1f p1 %d p99 %d clipped %.2f%% sat %.3f".format(mean, sd, p1, p99, clipped * 100, saturation)

    /** Accumulates packed ARGB or RGB pixels (`0xRRGGBB` in the low bytes). */
    class Builder {
        private val histogram = LongArray(LEVELS)
        private var saturationSum = 0.0

        fun add(rgb: Int) {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            histogram[((0.299 * r + 0.587 * g + 0.114 * b) + 0.5).toInt().coerceIn(0, LEVELS - 1)]++
            val max = maxOf(r, g, b)
            if (max > 0) saturationSum += (max - minOf(r, g, b)).toDouble() / max
        }

        fun add(r: Float, g: Float, b: Float) {
            histogram[(PhotoLook.luma(r, g, b) * (LEVELS - 1) + 0.5f).toInt().coerceIn(0, LEVELS - 1)]++
            saturationSum += PhotoLook.saturationOf(r, g, b)
        }

        fun build(): LumaStats {
            val n = histogram.sum().toDouble()
            val mean = histogram.indices.sumOf { it * histogram[it].toDouble() } / n
            val variance = histogram.indices.sumOf { (it - mean) * (it - mean) * histogram[it] } / n
            return LumaStats(
                mean,
                sqrt(variance),
                percentile(0.01, n),
                percentile(0.99, n),
                (histogram[LEVELS - 2] + histogram[LEVELS - 1]) / n,
                saturationSum / n
            )
        }

        private fun percentile(q: Double, n: Double): Int {
            var acc = 0L
            for (i in histogram.indices) {
                acc += histogram[i]
                if (acc >= q * n) return i
            }
            return LEVELS - 1
        }
    }

    private companion object {
        const val LEVELS = 256
    }
}
