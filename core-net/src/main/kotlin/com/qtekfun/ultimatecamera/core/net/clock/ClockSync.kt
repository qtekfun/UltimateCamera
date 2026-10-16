package com.qtekfun.ultimatecamera.core.net.clock

import kotlin.math.abs

/** A monotonic time source in nanoseconds. Production: `SystemClock.elapsedRealtimeNanos` (keeps counting in deep sleep). */
fun interface MonotonicClock {
    fun nanos(): Long
}

/**
 * One NTP-style exchange: client sends at [t0] (client clock), server receives at [t1] and replies at [t2] (server
 * clock), client receives at [t3] (client clock).
 */
data class ClockSample(val t0: Long, val t1: Long, val t2: Long, val t3: Long) {
    /** Round-trip delay without the server's processing time. */
    val delay: Long get() = (t3 - t0) - (t2 - t1)

    /** server − client, exact if the two network legs take the same time; off by at most [delay]/2 otherwise. */
    val offset: Long get() = ((t1 - t0) + (t2 - t3)) / 2

    val isPlausible: Boolean get() = t3 >= t0 && t2 >= t1 && delay >= 0
}

/** server clock ≈ client clock + [offsetNanos] + skew; [errorBoundNanos] bounds the error of [offsetNanos]. */
data class ClockEstimate(
    val offsetNanos: Long,
    val errorBoundNanos: Long,
    val minDelayNanos: Long,
    val samples: Int,
    /** Client time of the sample the offset belongs to; [skewPpm] extrapolates from it. */
    val referenceNanos: Long,
    val skewPpm: Double
) {
    /** Server time for client time [local]. */
    fun toServer(local: Long): Long = local + offsetNanos + skewCorrection(local)

    /** Client time for server time [server] (inverse of [toServer], exact to well under a microsecond). */
    fun toClient(server: Long): Long {
        val first = server - offsetNanos
        val second = first - skewCorrection(first)
        return first - skewCorrection(second)
    }

    private fun skewCorrection(local: Long): Long = ((local - referenceNanos) * skewPpm / PPM).toLong()

    private companion object {
        const val PPM = 1_000_000.0
    }
}

/**
 * Robust clock-offset estimator between a camera (client) and the clock source (server), REQ-NET-005.
 *
 * Wi-Fi delay is noisy and one-sided (queues, power-save wake-ups, retransmissions add delay, nothing removes it), so
 * the offset of the sample with the smallest round trip is the most accurate: its error is at most half of that round
 * trip. The estimator keeps a sliding window of [windowSize] samples and uses the minimum-delay one (Cristian / NTP
 * clock filter). Skew (crystal drift, typically tens of ppm) is estimated from the minimum-delay samples of the older
 * and newer halves of the window once they are at least [minSkewSpanNanos] apart, so long takes and long sessions
 * stay aligned between syncs. Pure (times come from the caller) and thread-safe: the clock client adds samples while the camera logic reads.
 */
class ClockEstimator(
    private val windowSize: Int = DEFAULT_WINDOW,
    private val minSkewSpanNanos: Long = DEFAULT_MIN_SKEW_SPAN_NANOS,
    private val maxSkewPpm: Double = DEFAULT_MAX_SKEW_PPM
) {
    private val window = ArrayDeque<ClockSample>()

    /** Adds a sample; false (ignored) when it is implausible or slower than [MAX_DELAY_NANOS]. */
    @Synchronized
    fun add(sample: ClockSample): Boolean {
        if (!sample.isPlausible || sample.delay > MAX_DELAY_NANOS) return false
        window.addLast(sample)
        while (window.size > windowSize) window.removeFirst()
        revision++
        return true
    }

    @Synchronized
    fun clear() {
        window.clear()
        revision++
    }

    /** Grows on every change, so views can cache the estimate. */
    @Volatile
    var revision: Long = 0
        private set

    val size: Int @Synchronized get() = window.size

    /** Current estimate, or null with fewer than [minSamples] samples. */
    @Synchronized
    fun estimate(minSamples: Int = DEFAULT_MIN_SAMPLES): ClockEstimate? {
        if (window.size < minSamples || window.isEmpty()) return null
        val best = window.minBy { it.delay }
        return ClockEstimate(
            offsetNanos = best.offset,
            errorBoundNanos = best.delay / 2,
            minDelayNanos = best.delay,
            samples = window.size,
            referenceNanos = best.t0 + (best.t3 - best.t0) / 2,
            skewPpm = skew()
        )
    }

    private fun skew(): Double {
        if (window.size < SKEW_MIN_SAMPLES) return 0.0
        val half = window.size / 2
        val older = window.take(half).minBy { it.delay }
        val newer = window.drop(half).minBy { it.delay }
        val span = newer.t0 - older.t0
        if (span < minSkewSpanNanos) return 0.0
        val ppm = (newer.offset - older.offset).toDouble() / span * PPM
        return if (abs(ppm) <= maxSkewPpm) ppm else 0.0
    }

    companion object {
        const val DEFAULT_WINDOW = 64
        const val DEFAULT_MIN_SAMPLES = 8

        /** Samples slower than this (a Wi-Fi stall) say nothing useful about the offset. */
        const val MAX_DELAY_NANOS = 250_000_000L
        const val DEFAULT_MIN_SKEW_SPAN_NANOS = 20_000_000_000L
        const val DEFAULT_MAX_SKEW_PPM = 200.0
        private const val SKEW_MIN_SAMPLES = 16
        private const val PPM = 1_000_000.0
    }
}
