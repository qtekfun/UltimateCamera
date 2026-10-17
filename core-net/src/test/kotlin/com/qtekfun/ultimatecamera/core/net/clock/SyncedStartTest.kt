package com.qtekfun.ultimatecamera.core.net.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncedStartTest {
    private val s = 1_000_000_000L
    private val ms = 1_000_000L

    /** 2026-10-17 00:00:00 UTC. */
    private val midnight = 1_792_195_200L * s

    private class Settable(var now: Long) : MonotonicClock {
        override fun nanos(): Long = now
    }

    /** A synced view where common = local + [offset]. */
    private class Shifted(private val offset: Long) : CommonClockView {
        override fun toCommon(local: Long): Long = local + offset

        override fun toLocal(common: Long): Long = common - offset

        override val errorBoundNanos: Long = 1_000L
    }

    private object Unsynced : CommonClockView {
        override fun toCommon(local: Long): Long? = null

        override fun toLocal(common: Long): Long? = null

        override val errorBoundNanos: Long? = null
    }

    @Test
    fun bridgeConvertsBetweenBootAndMonotonicClocks() {
        val boot = Settable(100 * s) // elapsedRealtimeNanos: counts deep sleep too
        val mono = Settable(40 * s) // System.nanoTime of an UNKNOWN-source sensor
        val bridge = CameraClockBridge(boot, mono)
        assertEquals(-60 * s, bridge.offsetNanos())
        assertEquals(45 * s, bridge.toCamera(105 * s))
        assertEquals(105 * s, bridge.toLocal(45 * s))
        // A REALTIME sensor shares the boot clock: identity.
        val same = CameraClockBridge(boot, boot)
        assertEquals(123L, same.toCamera(123L))
    }

    @Test
    fun armConvertsTheCommonInstantToTheCameraClock() {
        val boot = Settable(100 * s)
        val bridge = CameraClockBridge(boot, Settable(40 * s))
        val common = Shifted(midnight) // local 0 = midnight
        val at = midnight + 100 * s + 400 * ms
        assertEquals(SyncedStart.Arm.At(40 * s + 400 * ms), SyncedStart.arm(at, common, bridge, nowLocal = boot.now))
        // 30 ms late: start at once, report the delay.
        assertEquals(SyncedStart.Arm.Late(40 * s, 30 * ms), SyncedStart.arm(midnight + 100 * s - 30 * ms, common, bridge, nowLocal = boot.now))
        assertEquals(SyncedStart.Arm.NotSynced, SyncedStart.arm(at, Unsynced, bridge, nowLocal = boot.now))
        assertEquals(SyncedStart.Arm.TooFar, SyncedStart.arm(at + 120 * s, common, bridge, nowLocal = boot.now))
    }

    @Test
    fun planIsAFrameBoundaryOfTheTimeOfDayTimecode() {
        val now = midnight + 3_600 * s + 123_456_789L
        for (rate in listOf(FrameRate.FPS_24, FrameRate.FPS_25, FrameRate.FPS_30, FrameRate.FPS_60, FrameRate.FPS_29_97, FrameRate.FPS_59_94)) {
            val at = SyncedStart.plan(now, listOf(20 * ms, 50 * ms), rate, utcOffsetSeconds = 3_600)
            val earliest = now + ScheduledStart.MIN_MARGIN_NANOS + 100 * ms
            assertTrue(at >= earliest)
            assertTrue(at - earliest < s / 23) // at most one frame later
            // First instant of its label: a nanosecond earlier is the previous frame.
            assertTrue(SyncedStart.timecode(at, rate, 3_600) != SyncedStart.timecode(at - 1_000, rate, 3_600))
        }
    }

    @Test
    fun takeTimecodeFormatsAtTheTakeRate() {
        val t = midnight + (14 * 3_600 + 2 * 60 + 3) * s + 500 * ms
        assertEquals("14:02:03:12", SyncedStart.timecode(t, FrameRate.FPS_25, 0).toString())
        assertEquals("16:02:03:30", SyncedStart.timecode(t, FrameRate.FPS_60, 7_200).toString())
        // 29.97 is labelled drop-frame (semicolon), 30 is not.
        assertTrue(SyncedStart.timecode(t, FrameRate.FPS_29_97, 0).toString().contains(';'))
        assertFalse(SyncedStart.timecode(t, FrameRate.FPS_30, 0).dropFrame)
    }

    @Test
    fun timeOfDayUsesTheCommonClockWhenSyncedElseTheWallClock() {
        val boot = Settable(100 * s)
        val mono = Settable(40 * s)
        var common: CommonClockView? = null
        val tod = TimeOfDay(CameraClockBridge(boot, mono), { common }, wallNanos = { midnight + 10 * s })
        // Phone wall clock: camera now is wall now; a frame 1 s ago is 1 s earlier.
        assertFalse(tod.isCommon)
        assertEquals(midnight + 9 * s, tod.epochNanos(39 * s))
        common = Unsynced
        assertEquals(midnight + 9 * s, tod.epochNanos(39 * s))
        common = Shifted(midnight + 3_600 * s)
        assertTrue(tod.isCommon)
        // Camera 39 s = local 99 s = common midnight + 1 h + 99 s.
        assertEquals(midnight + 3_699 * s, tod.epochNanos(39 * s))
    }
}
