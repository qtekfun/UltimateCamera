package com.qtekfun.ultimatecamera.feature.spike

import com.qtekfun.ultimatecamera.feature.spike.pipeline.DurationStats
import com.qtekfun.ultimatecamera.feature.spike.pipeline.TimestampLog
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStatsTest {
    private val period60 = 16_666_667L

    @Test
    fun steadyCadenceHasNoDrops() {
        val stats = TimestampLog.stats(LongArray(61) { it * period60 }, 60)
        assertEquals(0, stats.dropped)
        assertEquals(60.0, stats.fps, 0.01)
    }

    @Test
    fun gapsCountTheMissingFrames() {
        // Frames 10, 11 and 12 missing (one gap of 4 periods), frame 30 missing (one gap of 2 periods).
        val ts = (0..60).filterNot { it in 10..12 || it == 30 }.map { it * period60 }.toLongArray()
        val stats = TimestampLog.stats(ts, 60)
        assertEquals(4, stats.dropped)
        assertEquals(4 * period60 / 1_000_000.0, stats.maxGapMs, 0.01)
    }

    @Test
    fun jitterBelowHalfAPeriodIsNotADrop() {
        val ts = LongArray(60) { it * period60 + if (it % 2 == 0) 4_000_000L else 0L }
        assertEquals(0, TimestampLog.stats(ts, 60).dropped)
    }

    @Test
    fun logStopsAtCapacityAndCountsOverflow() {
        val log = TimestampLog(2)
        repeat(5) { log.add(it.toLong()) }
        assertEquals(2, log.size)
        assertEquals(3, log.overflow)
    }

    @Test
    fun durationPercentiles() {
        val stats = DurationStats.of(LongArray(101) { it * 1_000_000L })!!
        assertEquals(50.0, stats.p50Ms, 0.0)
        assertEquals(95.0, stats.p95Ms, 0.0)
        assertEquals(100.0, stats.maxMs, 0.0)
    }
}
