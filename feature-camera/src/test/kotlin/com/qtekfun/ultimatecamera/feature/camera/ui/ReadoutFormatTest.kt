package com.qtekfun.ultimatecamera.feature.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadoutFormatTest {
    @Test
    fun timecodeCountsFramesAtTheTakeRate() {
        assertEquals("00:00:00:00", formatTimecode(0, 30))
        assertEquals("00:00:01:15", formatTimecode(1_500, 30))
        assertEquals("00:01:05:59", formatTimecode(65_999, 60))
        assertEquals("01:00:00:00", formatTimecode(3_600_000, 25))
    }

    @Test
    fun timecodeSurvivesUnknownRateAndNegativeTime() {
        assertEquals("00:00:02:00", formatTimecode(2_500, 0))
        assertEquals("00:00:00:00", formatTimecode(-10, 30))
    }

    @Test
    fun peakToDbIsFlooredAndCapped() {
        assertEquals(-60f, peakToDb(0f), 0f)
        assertEquals(0f, peakToDb(1f), 0f)
        assertEquals(-6.02f, peakToDb(0.5f), 0.01f)
    }

    @Test
    fun meterScaleIsMonotonicAndGivesTheTopTwentyDbHalfTheBar() {
        assertEquals(0f, meterFraction(-60f), 1e-6f)
        assertEquals(0.5f, meterFraction(-20f), 1e-6f)
        assertEquals(1f, meterFraction(0f), 1e-6f)
        var previous = -1f
        for (db in -60..0) {
            val f = meterFraction(db.toFloat())
            assertTrue(f > previous)
            previous = f
        }
    }
}
