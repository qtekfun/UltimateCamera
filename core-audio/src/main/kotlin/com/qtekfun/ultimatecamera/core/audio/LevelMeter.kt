package com.qtekfun.ultimatecamera.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Peak and RMS of 16-bit PCM in dBFS (REQ-AUD-004 groundwork). Accumulates across [add] calls without allocating. */
class LevelMeter {
    private var peak = 0
    private var sumSquares = 0.0
    private var count = 0L

    fun add(samples: ShortArray, n: Int) {
        for (i in 0 until n) {
            val s = samples[i].toInt()
            peak = max(peak, abs(s))
            sumSquares += s.toDouble() * s
        }
        count += n
    }

    val peakDbfs: Double get() = toDbfs(peak.toDouble())
    val rmsDbfs: Double get() = if (count == 0L) Double.NEGATIVE_INFINITY else toDbfs(sqrt(sumSquares / count))

    private fun toDbfs(amplitude: Double): Double = if (amplitude <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(amplitude / FULL_SCALE)

    private companion object {
        const val FULL_SCALE = 32768.0
    }
}
