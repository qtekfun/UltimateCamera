package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingGuardTest {
    private val cool = PowerManager.THERMAL_STATUS_NONE
    private val limit = RecordingGuard.FAT32_SPLIT_BYTES

    private fun guard(splitLimit: Long? = null) = RecordingGuard().apply { reset(splitLimit) }

    @Test
    fun calmPhoneDoesNothing() {
        assertEquals(GuardAction.None, guard(limit).decide(cool, batteryPercent = 80, charging = false, storageFull = false, partBytes = 1_000))
    }

    @Test
    fun criticalHeatStopsOnce() {
        val g = guard()
        assertEquals(GuardAction.Stop(StopReason.THERMAL), g.decide(PowerManager.THERMAL_STATUS_CRITICAL, 80, false, false, 0))
        assertEquals(GuardAction.None, g.decide(PowerManager.THERMAL_STATUS_SHUTDOWN, 80, false, false, 0))
    }

    @Test
    fun severeHeatWarnsOncePerTake() {
        val g = guard()
        assertEquals(GuardAction.WarnThermal, g.decide(PowerManager.THERMAL_STATUS_SEVERE, 80, false, false, 0))
        assertEquals(GuardAction.None, g.decide(PowerManager.THERMAL_STATUS_SEVERE, 80, false, false, 0))
        g.reset(null)
        assertEquals(GuardAction.WarnThermal, g.decide(PowerManager.THERMAL_STATUS_SEVERE, 80, false, false, 0))
    }

    @Test
    fun lowBatteryStopsOnlyWhenNotCharging() {
        assertEquals(GuardAction.None, guard().decide(cool, 3, charging = true, storageFull = false, partBytes = 0))
        assertEquals(GuardAction.None, guard().decide(cool, 4, charging = false, storageFull = false, partBytes = 0))
        assertEquals(GuardAction.Stop(StopReason.BATTERY), guard().decide(cool, 3, charging = false, storageFull = false, partBytes = 0))
        // Unknown battery level never stops a take.
        assertEquals(GuardAction.None, guard().decide(cool, -1, charging = false, storageFull = false, partBytes = 0))
    }

    @Test
    fun fullStorageStops() {
        assertEquals(GuardAction.Stop(StopReason.STORAGE_FULL), guard().decide(cool, 80, false, storageFull = true, partBytes = 0))
    }

    @Test
    fun heatWinsOverBatteryAndStorage() {
        assertEquals(GuardAction.Stop(StopReason.THERMAL), guard().decide(PowerManager.THERMAL_STATUS_EMERGENCY, 1, false, true, 0))
    }

    @Test
    fun splitsOncePerPartAndAgainForTheNext() {
        val g = guard(limit)
        assertEquals(GuardAction.None, g.decide(cool, 80, false, false, limit - 1))
        assertEquals(GuardAction.Split, g.decide(cool, 80, false, false, limit))
        // Still the old part (the split is in progress): no second request.
        assertEquals(GuardAction.None, g.decide(cool, 80, false, false, limit + 1_000))
        // The new part is small, then reaches the limit again.
        assertEquals(GuardAction.None, g.decide(cool, 80, false, false, 10_000))
        assertEquals(GuardAction.Split, g.decide(cool, 80, false, false, limit + 5))
    }

    @Test
    fun noSplitWithoutLimitOrUnknownSize() {
        assertEquals(GuardAction.None, guard(null).decide(cool, 80, false, false, Long.MAX_VALUE))
        assertEquals(GuardAction.None, guard(limit).decide(cool, 80, false, false, -1))
    }

    @Test
    fun splitLimitOnlyOffThePrimaryVolume() {
        assertNull(RecordingGuard.splitLimit(RecordingLocation.Default))
        assertNull(RecordingGuard.splitLimit(null))
        val primary = RecordingLocation.Folder("content://com.android.externalstorage.documents/tree/primary%3AMovies")
        assertNull(RecordingGuard.splitLimit(primary))
        val usb = RecordingLocation.Folder("content://com.android.externalstorage.documents/tree/1234-ABCD%3AVideo")
        assertEquals(limit, RecordingGuard.splitLimit(usb))
    }

    @Test
    fun fat32LimitLeavesRoomUnderFourGibibytes() {
        val fat32Max = 4L * 1024 * 1024 * 1024 - 1
        assertTrue(limit < fat32Max - 100L * 1024 * 1024)
        assertTrue(limit > 3_900_000_000L)
    }

    @Test
    fun droppedFramesFromElapsedAndFps() {
        assertEquals(0, RecordingGuard.droppedFrames(elapsedMs = 10_000, fps = 30, encodedFrames = 300))
        // Pipeline latency is not a drop.
        assertEquals(0, RecordingGuard.droppedFrames(elapsedMs = 10_000, fps = 30, encodedFrames = 297))
        assertEquals(7, RecordingGuard.droppedFrames(elapsedMs = 10_000, fps = 30, encodedFrames = 290))
        assertEquals(0, RecordingGuard.droppedFrames(elapsedMs = 0, fps = 60, encodedFrames = 0))
    }

    @Test
    fun bitrateFromBytesAndTime() {
        assertEquals(100_000_000L, RecordingGuard.bitsPerSecond(bytes = 25_000_000, millis = 2_000))
        assertEquals(0L, RecordingGuard.bitsPerSecond(bytes = 5, millis = 0))
    }
}
