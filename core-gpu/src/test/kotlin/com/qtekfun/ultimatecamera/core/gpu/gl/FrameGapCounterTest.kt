package com.qtekfun.ultimatecamera.core.gpu.gl

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGapCounterTest {
    private val interval60 = 1_000_000_000L / 60

    private fun FrameGapCounter.feed(timestamps: List<Long>) = timestamps.forEach(::onFrame)

    @Test
    fun oneMissingFrameIsOneGapOfTwoIntervals() {
        val c = FrameGapCounter().apply { expectedIntervalNanos = interval60 }
        // Frames 0..9 at 60 fps with frame 5 missing (the OPPO 1080p60 probe of D83).
        c.feed((0L..9L).filter { it != 5L }.map { it * interval60 })
        assertEquals(1, c.drops.get())
        assertEquals(1, c.gaps.get())
        assertEquals(2 * interval60, c.maxStepNanos)
    }

    @Test
    fun aLongGapCountsItsMissingFramesButIsOneGap() {
        val c = FrameGapCounter().apply { expectedIntervalNanos = interval60 }
        c.feed(listOf(0L, interval60, 6 * interval60, 7 * interval60))
        assertEquals(4, c.drops.get())
        assertEquals(1, c.gaps.get())
    }

    @Test
    fun clockDriftIsNoGapButTheLargestStepIsKept() {
        val c = FrameGapCounter().apply { expectedIntervalNanos = interval60 }
        val step = interval60 * 102 / 100
        c.feed((0L..20L).map { it * step })
        assertEquals(0, c.drops.get())
        assertEquals(0, c.gaps.get())
        assertEquals(step, c.maxStepNanos)
    }

    @Test
    fun aWindowKeepsItsOwnLargestStepAndResetForgetsEverything() {
        val c = FrameGapCounter().apply { expectedIntervalNanos = interval60 }
        c.feed(listOf(0L, 3 * interval60, 4 * interval60))
        c.startWindow()
        c.feed(listOf(5 * interval60, 6 * interval60))
        assertEquals(interval60, c.maxStepNanos)
        assertEquals(2, c.drops.get()) // Drops keep counting across windows: read as differences.
        c.reset()
        c.feed(listOf(100 * interval60))
        assertEquals(0, c.drops.get())
        assertEquals(0, c.gaps.get())
        assertEquals(0L, c.maxStepNanos)
    }

    @Test
    fun noIntervalCountsNoGaps() {
        val c = FrameGapCounter()
        c.feed(listOf(0L, 10 * interval60))
        assertEquals(0, c.drops.get())
        assertEquals(10 * interval60, c.maxStepNanos)
    }
}
