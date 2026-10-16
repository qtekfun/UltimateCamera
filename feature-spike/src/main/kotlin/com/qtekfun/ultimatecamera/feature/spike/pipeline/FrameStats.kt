package com.qtekfun.ultimatecamera.feature.spike.pipeline

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** Frame-timing summary over a measurement window. */
@Serializable
data class FrameStats(
    val frames: Int,
    val fps: Double,
    /** Frames missing from the expected cadence (a gap of k periods counts k - 1). */
    val dropped: Int,
    val maxGapMs: Double
)

/**
 * Collects frame timestamps (ns) into a preallocated array, so recording a frame never allocates on the camera or GL
 * thread. Single writer; read [stats] after the writer stopped.
 */
class TimestampLog(capacity: Int) {
    private val values = LongArray(capacity)

    @Volatile var size = 0
        private set

    @Volatile var overflow = 0
        private set

    fun add(timestampNs: Long) {
        if (size < values.size) values[size++] = timestampNs else overflow++
    }

    fun stats(expectedFps: Int): FrameStats = stats(values.copyOf(size), expectedFps)

    companion object {
        fun stats(timestamps: LongArray, expectedFps: Int): FrameStats {
            if (timestamps.size < 2) return FrameStats(timestamps.size, 0.0, 0, 0.0)
            val sorted = timestamps.sortedArray()
            val period = 1_000_000_000.0 / expectedFps
            var dropped = 0
            var maxGap = 0L
            for (i in 1 until sorted.size) {
                val gap = sorted[i] - sorted[i - 1]
                if (gap > maxGap) maxGap = gap
                if (gap > period * 1.5) dropped += (gap / period).roundToInt() - 1
            }
            val span = (sorted.last() - sorted.first()) / 1_000_000_000.0
            return FrameStats(sorted.size, (sorted.size - 1) / span, dropped, maxGap / 1_000_000.0)
        }
    }
}

/** Per-frame CPU time on the render thread, as percentiles in milliseconds. */
@Serializable
data class DurationStats(val p50Ms: Double, val p95Ms: Double, val p99Ms: Double, val maxMs: Double) {
    companion object {
        fun of(nanos: LongArray): DurationStats? {
            if (nanos.isEmpty()) return null
            val s = nanos.sortedArray()
            fun p(q: Double) = s[((s.size - 1) * q).roundToInt()] / 1_000_000.0
            return DurationStats(p(0.50), p(0.95), p(0.99), s.last() / 1_000_000.0)
        }
    }
}
