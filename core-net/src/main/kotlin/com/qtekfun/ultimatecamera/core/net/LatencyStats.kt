package com.qtekfun.ultimatecamera.core.net

import kotlin.math.ceil
import kotlinx.serialization.Serializable

/** Round-trip statistics in milliseconds; percentiles use the nearest-rank method. */
@Serializable
data class LatencyStats(
    val sent: Int,
    val received: Int,
    val lossPercent: Double,
    val minMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double
) {
    companion object {
        fun of(sent: Int, rttMs: List<Double>): LatencyStats {
            require(sent >= rttMs.size) { "received more packets than sent" }
            val sorted = rttMs.sorted()
            fun rank(p: Double): Double =
                if (sorted.isEmpty()) Double.NaN else sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
            return LatencyStats(
                sent = sent,
                received = sorted.size,
                lossPercent = if (sent == 0) 0.0 else 100.0 * (sent - sorted.size) / sent,
                minMs = sorted.firstOrNull() ?: Double.NaN,
                p50Ms = rank(50.0),
                p95Ms = rank(95.0),
                p99Ms = rank(99.0),
                maxMs = sorted.lastOrNull() ?: Double.NaN
            )
        }
    }
}
