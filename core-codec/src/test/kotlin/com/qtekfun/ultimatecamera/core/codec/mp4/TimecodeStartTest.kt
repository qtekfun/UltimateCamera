package com.qtekfun.ultimatecamera.core.codec.mp4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimecodeStartTest {
    private fun df30(frame: Long) = TimecodeStart.label(frame, 30_000, 1_001, dropFrame = true)

    @Test
    fun dropFrameSkipsTwoLabelsEachMinuteButEveryTenth() {
        assertEquals("00:00:59;29", df30(1_799))
        assertEquals("00:01:00;02", df30(1_800))
        assertEquals("00:09:59;29", df30(17_981))
        assertEquals("00:10:00;00", df30(17_982))
        assertEquals("00:10:01;00", df30(18_012))
        assertEquals("01:00:00;00", df30(107_892))
    }

    @Test
    fun dropFrameAt5994SkipsFourLabels() {
        assertEquals("00:00:59;59", TimecodeStart.label(3_599, 60_000, 1_001, dropFrame = true))
        assertEquals("00:01:00;04", TimecodeStart.label(3_600, 60_000, 1_001, dropFrame = true))
        assertEquals("00:10:00;00", TimecodeStart.label(35_964, 60_000, 1_001, dropFrame = true))
    }

    @Test
    fun onlyNtscRatesAreDropFrame() {
        assertTrue(TimecodeStart(0, 30_000, 1_001).dropFrame)
        assertTrue(TimecodeStart(0, 60_000, 1_001).dropFrame)
        assertFalse(TimecodeStart(0, 30).dropFrame)
        assertFalse(TimecodeStart(0, 24_000, 1_001).dropFrame)
        assertEquals(24, TimecodeStart(0, 24_000, 1_001).nominal)
    }

    @Test
    fun timeOfDayCountsFramesSinceLocalMidnight() {
        // 2026-10-17 12:10:05.5 UTC = 14:10:05.5 at UTC+2.
        val tc = TimecodeStart.ofTimeOfDay(1_792_239_005_500_000_000L, utcOffsetSeconds = 7_200, numerator = 25)
        assertEquals((14L * 3600 + 10 * 60 + 5) * 25 + 12, tc.frame)
        assertEquals("14:10:05:12", tc.label)
    }

    @Test
    fun oneRealHourAt2997IsOneHourOfDropFrameLabels() {
        val midnight = 1_792_195_200_000_000_000L // 2026-10-17 00:00:00 UTC
        val tc = TimecodeStart.ofTimeOfDay(midnight + 3_600_000_000_000L, 0, 30_000, 1_001)
        assertEquals(107_892L, tc.frame)
        assertEquals("01:00:00;00", tc.label)
    }
}
