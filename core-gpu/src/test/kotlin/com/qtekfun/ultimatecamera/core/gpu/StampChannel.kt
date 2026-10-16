package com.qtekfun.ultimatecamera.core.gpu

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Simulated video path for one luma row of the [FrameStamp] strip: what scaling, encoding at low bitrate, range
 * conversion and sensor/encoder noise do to it. Rows are float luma 0..255 until [toLuma] rounds and clamps them.
 */
internal object StampChannel {
    fun row(counter: Int, width: Int): FloatArray = FrameStamp.renderRow(counter, width).toFloatArray()

    fun IntArray.toFloatArray(): FloatArray = FloatArray(size) { this[it].toFloat() }

    fun toLuma(row: FloatArray): IntArray = IntArray(row.size) { row[it].roundToInt().coerceIn(0, 255) }

    /** Gaussian blur with standard deviation [sigma] pixels, edges clamped. */
    fun blur(row: FloatArray, sigma: Float): FloatArray {
        if (sigma <= 0f) return row.copyOf()
        val radius = ceil(sigma * 3f).toInt()
        val kernel = FloatArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2f * sigma * sigma)) }
        val total = kernel.sum()
        return FloatArray(row.size) { x ->
            var sum = 0f
            for (k in kernel.indices) sum += kernel[k] * row[(x + k - radius).coerceIn(0, row.lastIndex)]
            sum / total
        }
    }

    /**
     * Resamples to [newWidth] like a GPU or hardware scaler: bilinear at pixel centres, with an area pre-filter (box of
     * the scale factor) when shrinking so it does not alias.
     */
    fun scale(row: FloatArray, newWidth: Int): FloatArray {
        val ratio = row.size.toFloat() / newWidth
        val source = if (ratio > 1f) boxFilter(row, ratio) else row
        return FloatArray(newWidth) { x ->
            val s = ((x + 0.5f) * ratio - 0.5f).coerceIn(0f, (row.size - 1).toFloat())
            val i = floor(s).toInt().coerceAtMost(row.size - 2)
            val f = s - i
            source[i] * (1f - f) + source[i + 1] * f
        }
    }

    /** Shifts right by [pixels] (negative: left), repeating the edge pixel into the uncovered part. */
    fun shift(row: FloatArray, pixels: Int): FloatArray = FloatArray(row.size) { row[(it - pixels).coerceIn(0, row.lastIndex)] }

    /** Maps full range 0..255 to [low]..[high] (16..235 is limited-range video). */
    fun range(row: FloatArray, low: Float = 16f, high: Float = 235f): FloatArray = FloatArray(row.size) { low + row[it] / 255f * (high - low) }

    /** Applies a power curve with exponent [gamma] on the normalised level. */
    fun gamma(row: FloatArray, gamma: Float): FloatArray = FloatArray(row.size) { 255f * (row[it].coerceIn(0f, 255f) / 255f).pow(gamma) }

    fun noise(row: FloatArray, sigma: Float, random: Random): FloatArray = FloatArray(row.size) { row[it] + sigma * gaussian(random) }

    /**
     * Low-bitrate blocking: an 8-point DCT per 8-pixel block, coefficients quantised with step `q * (1 + k)` for
     * frequency `k` (DC with step `q`), and the inverse. The strip is vertically uniform inside a block row, so the 2D
     * transform of an 8×8 block reduces to this 1D one. [phase] moves the block grid relative to the row.
     */
    fun blocks(row: FloatArray, q: Float, phase: Int = 0): FloatArray {
        val out = row.copyOf()
        val coefficients = FloatArray(8)
        var start = -(phase % 8)
        while (start < row.size) {
            for (k in 0 until 8) {
                var sum = 0f
                for (n in 0 until 8) sum += row[(start + n).coerceIn(0, row.lastIndex)] * basis(n, k)
                val step = q * (1 + k)
                coefficients[k] = (sum / step).roundToInt() * step
            }
            for (n in 0 until 8) {
                val x = start + n
                if (x !in row.indices) continue
                var sum = 0f
                for (k in 0 until 8) sum += coefficients[k] * basis(n, k)
                out[x] = sum
            }
            start += 8
        }
        return out
    }

    fun gaussian(random: Random): Float {
        // Box-Muller.
        val u = random.nextDouble(1e-12, 1.0)
        val v = random.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u)) * cos(2.0 * PI * v)).toFloat()
    }

    /** Orthonormal DCT-II basis. */
    private fun basis(n: Int, k: Int): Float {
        val c = if (k == 0) sqrt(1.0 / 8) else sqrt(2.0 / 8)
        return (c * cos(PI * (2 * n + 1) * k / 16)).toFloat()
    }

    private fun boxFilter(row: FloatArray, size: Float): FloatArray {
        val half = (size / 2f).roundToInt().coerceAtLeast(1)
        return FloatArray(row.size) { x ->
            var sum = 0f
            for (k in -half + 1..half) sum += row[(x + k).coerceIn(0, row.lastIndex)]
            sum / (2 * half)
        }
    }
}
