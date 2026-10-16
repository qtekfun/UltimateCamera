package com.qtekfun.ultimatecamera.core.net.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimecodeTest {
    private val s = 1_000_000_000L

    /** 2026-10-17 00:00:00 UTC. */
    private val midnight = 1_792_195_200L * s

    @Test
    fun integerRatesMatchTheWallClock() {
        val t = midnight + (10 * 3_600 + 11 * 60 + 12) * s + 500_000_000L
        assertEquals("10:11:12:15", Timecode.ofTimeOfDay(t, 0, FrameRate.FPS_30).toString())
        assertEquals("10:11:12:12", Timecode.ofTimeOfDay(t, 0, FrameRate.FPS_25).toString())
        assertEquals("10:11:12:30", Timecode.ofTimeOfDay(t, 0, FrameRate.FPS_60).toString())
        // Madrid in summer (UTC+2) and a zone west of UTC.
        assertEquals("12:11:12:15", Timecode.ofTimeOfDay(t, 7_200, FrameRate.FPS_30).toString())
        assertEquals("05:11:12:15", Timecode.ofTimeOfDay(t, -5 * 3_600, FrameRate.FPS_30).toString())
        assertEquals("00:00:00:00", Timecode.ofTimeOfDay(midnight, 0, FrameRate.FPS_24).toString())
        assertEquals("23:59:59:23", Timecode.ofTimeOfDay(midnight - 1, 0, FrameRate.FPS_24).toString())
    }

    @Test
    fun dropFrameLabelsFollowSmpte() {
        val df = FrameRate.FPS_29_97
        fun tc(frame: Long) = Timecode.ofFrames(frame, df).toString()
        assertEquals("00:00:59;29", tc(1_799))
        assertEquals("00:01:00;02", tc(1_800)) // ;00 and ;01 are skipped
        assertEquals("00:02:00;02", tc(3_598))
        assertEquals("00:09:59;29", tc(17_981))
        assertEquals("00:10:00;00", tc(17_982)) // every tenth minute keeps ;00
        assertEquals("01:00:00;00", tc(107_892))
        assertEquals("00:01:00;04", Timecode.ofFrames(3_600, FrameRate.FPS_59_94).toString())
        assertEquals("00:01:00:00", Timecode.ofFrames(1_800, df, dropFrame = false).toString())
        // An hour of 29.97 DF labels is an hour of wall clock (within one frame).
        val hourLater = Timecode.ofTimeOfDay(midnight + 3_600 * s, 0, df)
        assertEquals(1, hourLater.hours)
        assertEquals(0, hourLater.minutes)
        assertTrue(hourLater.frames <= 1 && hourLater.seconds == 0)
    }

    @Test
    fun frameBoundariesAreExactForFractionalRates() {
        for (rate in listOf(FrameRate.FPS_23_976, FrameRate.FPS_29_97, FrameRate.FPS_59_94, FrameRate.FPS_25, FrameRate(120))) {
            for (frame in listOf(0L, 1L, 1_000L, 2_589_407L, 5_000_000L)) {
                val start = rate.startOf(frame)
                assertEquals("$rate $frame", frame, rate.framesIn(start))
                assertEquals("$rate $frame", frame - 1, rate.framesIn(start - 1_000))
            }
        }
    }

    @Test
    fun scheduledStartLandsOnAFrameBoundaryAfterTheMargin() {
        val now = midnight + 37 * s + 123_456_789L
        for (rate in listOf(FrameRate.FPS_30, FrameRate.FPS_29_97, FrameRate.FPS_23_976)) {
            val at = ScheduledStart.plan(now, ScheduledStart.MIN_MARGIN_NANOS, rate, utcOffsetSeconds = 7_200)
            assertTrue(at >= now + ScheduledStart.MIN_MARGIN_NANOS)
            assertTrue(at - (now + ScheduledStart.MIN_MARGIN_NANOS) < s / 23)
            // The start is the first instant of its frame: one microsecond earlier is the previous label.
            val label = Timecode.ofTimeOfDay(at, 7_200, rate)
            val before = Timecode.ofTimeOfDay(at - 1_000, 7_200, rate)
            assertTrue("$rate $label $before", label != before)
        }
        assertEquals(now + 500_000_000L, ScheduledStart.plan(now, 500_000_000L))
    }

    @Test
    fun marginGrowsWithTheWorstRoundTripWithinLimits() {
        assertEquals(ScheduledStart.MIN_MARGIN_NANOS, ScheduledStart.margin(emptyList()))
        assertEquals(ScheduledStart.MIN_MARGIN_NANOS + 80_000_000L, ScheduledStart.margin(listOf(5_000_000L, 40_000_000L)))
        assertEquals(ScheduledStart.MAX_MARGIN_NANOS, ScheduledStart.margin(listOf(10 * s)))
    }

    @Test
    fun cameraConvertsTheStartToItsOwnClock() {
        val view = SourceClock({ 0L }, epochNanosAtStart = midnight) // local 0 = midnight
        assertEquals(ScheduledStart.Local.At(2 * s), ScheduledStart.toLocal(midnight + 2 * s, view, nowLocal = s))
        assertEquals(ScheduledStart.Local.Late(s), ScheduledStart.toLocal(midnight + s, view, nowLocal = 2 * s))
        assertEquals(ScheduledStart.Local.TooFar, ScheduledStart.toLocal(midnight + 120 * s, view, nowLocal = 0))
        assertEquals(ScheduledStart.Local.NotSynced, ScheduledStart.toLocal(midnight, SyncedClock(ClockEstimator()), nowLocal = 0))
    }
}
