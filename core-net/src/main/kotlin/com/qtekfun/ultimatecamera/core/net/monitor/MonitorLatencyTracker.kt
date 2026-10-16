package com.qtekfun.ultimatecamera.core.net.monitor

import com.qtekfun.ultimatecamera.core.net.LatencyStats
import kotlinx.serialization.Serializable

/** Latency of the monitor link as seen by the camera phone. All times in milliseconds. */
@Serializable
data class MonitorLatency(
    /** Stamped frames submitted while measuring. */
    val framesSubmitted: Int,
    /** Echoes that arrived, matched or not. */
    val echoesReceived: Int,
    /** Echoes whose counter matched a submitted frame that had not been echoed yet. */
    val echoesMatched: Int,
    /** Matched echoes that arrived before the first RTT sample, so carry no latency value. */
    val echoesWithoutRtt: Int,
    /**
     * One-way latency per matched echo: (echo arrival - submit time) - RTT/2, with RTT the smoothed data-channel round
     * trip at that moment. It covers our render into the encoder surface, encode, network, jitter buffer and decode on
     * the monitor; not camera exposure nor the monitor's display. `sent` is [framesSubmitted], so `lossPercent` is the
     * share of frames that never came back (dropped, not decoded, or stamp unreadable).
     */
    val glassToGlass: LatencyStats,
    /** Data-channel round trips (ping/pong). `sent` is the number of pings. */
    val controlRtt: LatencyStats
)

/**
 * Latency without clock sync. The camera phone stamps each proxy frame with a counter and records when it submitted
 * it ([frameSubmitted]); the monitor reads the counter from the decoded frame and echoes it straight back over the data
 * channel ([onEcho]). Half of the data-channel RTT ([onPong]) stands in for the echo's way back.
 *
 * [frameSubmitted] runs on the render thread: it writes into preallocated rings and never allocates. All methods are
 * thread-safe; the lock is held only for a few array writes, so the render thread never waits on the network. Frames
 * are tracked in a ring of [capacity] slots indexed by counter, so an echo older than [capacity] frames is unmatched.
 */
class MonitorLatencyTracker(private val capacity: Int = DEFAULT_CAPACITY, maxSamples: Int = DEFAULT_MAX_SAMPLES) {
    init {
        require(capacity > 0 && maxSamples > 0)
    }

    private val lock = Any()
    private val counters = IntArray(capacity)
    private val sentNanos = LongArray(capacity)
    private val pending = BooleanArray(capacity)
    private val latencyMs = DoubleArray(maxSamples)
    private val rttMs = DoubleArray(maxSamples)

    private var framesSubmitted = 0
    private var echoesReceived = 0
    private var echoesMatched = 0
    private var echoesWithoutRtt = 0
    private var latencyCount = 0
    private var pingsSent = 0
    private var rttCount = 0

    /** Smoothed RTT in nanoseconds (RFC 6298 weights), or -1 before the first pong. */
    private var smoothedRttNanos = -1L

    /** Smoothed data-channel RTT in milliseconds, or NaN before the first pong. */
    val smoothedRttMs: Double get() = synchronized(lock) { if (smoothedRttNanos < 0) Double.NaN else smoothedRttNanos / NANOS_PER_MS }

    /** The frame with [counter] went into the encoder surface at [nanoTime] (System.nanoTime). */
    fun frameSubmitted(counter: Int, nanoTime: Long) {
        val slot = Math.floorMod(counter, capacity)
        synchronized(lock) {
            counters[slot] = counter
            sentNanos[slot] = nanoTime
            pending[slot] = true
            framesSubmitted++
        }
    }

    /** The monitor decoded [counter]; the echo arrived at [nowNanos]. Returns true when it matched a submitted frame. */
    fun onEcho(counter: Int, nowNanos: Long): Boolean {
        val slot = Math.floorMod(counter, capacity)
        synchronized(lock) {
            echoesReceived++
            if (!pending[slot] || counters[slot] != counter) return false
            pending[slot] = false
            echoesMatched++
            val rtt = smoothedRttNanos
            if (rtt < 0) {
                echoesWithoutRtt++
            } else if (latencyCount < latencyMs.size) {
                latencyMs[latencyCount++] = (nowNanos - sentNanos[slot] - rtt / 2) / NANOS_PER_MS
            }
            return true
        }
    }

    fun pingSent() {
        synchronized(lock) { pingsSent++ }
    }

    /** A pong for a ping sent at [sentNanos] arrived at [nowNanos]. */
    fun onPong(sentNanos: Long, nowNanos: Long) {
        val rtt = nowNanos - sentNanos
        if (rtt < 0) return
        synchronized(lock) {
            smoothedRttNanos = if (smoothedRttNanos < 0) rtt else smoothedRttNanos + (rtt - smoothedRttNanos) / RTT_SMOOTHING
            if (rttCount < rttMs.size) rttMs[rttCount++] = rtt / NANOS_PER_MS
        }
    }

    /** Forgets everything measured so far (for a warm-up), but keeps the RTT estimate. */
    fun reset() {
        synchronized(lock) {
            pending.fill(false)
            framesSubmitted = 0
            echoesReceived = 0
            echoesMatched = 0
            echoesWithoutRtt = 0
            latencyCount = 0
            pingsSent = 0
            rttCount = 0
        }
    }

    fun snapshot(): MonitorLatency {
        val latencies: List<Double>
        val rtts: List<Double>
        val submitted: Int
        val pings: Int
        val result = synchronized(lock) {
            latencies = latencyMs.copyOf(latencyCount).asList()
            rtts = rttMs.copyOf(rttCount).asList()
            submitted = framesSubmitted
            pings = maxOf(pingsSent, rttCount)
            MonitorLatency(framesSubmitted, echoesReceived, echoesMatched, echoesWithoutRtt, EMPTY, EMPTY)
        }
        // Sorting happens outside the lock.
        return result.copy(glassToGlass = LatencyStats.of(submitted, latencies), controlRtt = LatencyStats.of(pings, rtts))
    }

    companion object {
        /** About 17 s of frames at 30 fps: far more than any plausible echo delay. */
        const val DEFAULT_CAPACITY = 512

        /** Ten minutes at 60 fps. */
        const val DEFAULT_MAX_SAMPLES = 36_000

        private const val NANOS_PER_MS = 1_000_000.0
        private const val RTT_SMOOTHING = 8
        private val EMPTY = LatencyStats.of(0, emptyList())
    }
}
