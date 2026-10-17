package com.qtekfun.ultimatecamera.core.gpu.gl

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderTimeWindowTest {
    @Test
    fun emptyWindowIsZero() {
        assertEquals(0L, RenderTimeWindow(4).percentile(0.95))
    }

    @Test
    fun p95IsNearestRank() {
        val window = RenderTimeWindow(100)
        (1L..100L).shuffled(java.util.Random(1)).forEach(window::record)
        assertEquals(95L, window.percentile(0.95))
        assertEquals(100L, window.percentile(1.0))
        assertEquals(1L, window.percentile(0.0))
    }

    @Test
    fun oldSamplesLeaveTheWindow() {
        val window = RenderTimeWindow(3)
        listOf(100L, 100L, 100L, 1L, 2L, 3L).forEach(window::record)
        assertEquals(3L, window.percentile(0.95))
    }

    @Test
    fun clearEmptiesTheWindow() {
        val window = RenderTimeWindow(3)
        window.record(5)
        window.clear()
        assertEquals(0L, window.percentile(0.5))
    }
}
