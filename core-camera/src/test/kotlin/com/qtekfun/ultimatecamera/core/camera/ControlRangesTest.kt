package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRangesTest {
    private val full = ControlRanges(
        isoRange = 50..6400,
        exposureTimeRangeNs = 10_000L..500_000_000L,
        evRange = -12..12,
        evStep = 1f / 6,
        minFocusDiopters = 10f,
        manualFocus = true,
        whiteBalances = WhiteBalance.entries.toSet() - WhiteBalance.MANUAL,
        kelvinRange = 2000..10000,
        aeLock = true,
        awbLock = true,
        maxAeRegions = 1,
        maxAfRegions = 1
    )

    @Test
    fun `values clamp to the camera ranges`() {
        val c = full.clamp(
            CameraControls(
                manualExposure = true,
                iso = 20,
                exposureTimeNs = 1L,
                exposureCompensation = 40,
                focusDiopters = 30f,
                manualFocus = true,
                kelvin = 1000,
                meteringPoint = ImagePoint(-1f, 2f)
            ),
            fps = 30
        )
        assertEquals(50, c.iso)
        assertEquals(10_000L, c.exposureTimeNs)
        assertEquals(12, c.exposureCompensation)
        assertEquals(10f, c.focusDiopters)
        assertEquals(2000, c.kelvin)
        assertEquals(ImagePoint(0f, 1f), c.meteringPoint)
        assertTrue(c.manualExposure && c.manualFocus)
    }

    @Test
    fun `exposure never exceeds the frame time`() {
        val c = full.clamp(CameraControls(manualExposure = true, exposureTimeNs = 100_000_000L), fps = 60)
        assertEquals(ControlMath.frameDurationNs(60), c.exposureTimeNs)
        assertEquals(ControlMath.frameDurationNs(30), full.shutterStops(30).last())
    }

    @Test
    fun `unsupported controls fall back to auto`() {
        val basic = ControlRanges(evRange = -4..4)
        val c = basic.clamp(
            CameraControls(manualExposure = true, manualFocus = true, whiteBalance = WhiteBalance.MANUAL, aeLock = true, awbLock = true, afLock = true),
            fps = 30
        )
        assertFalse(c.manualExposure)
        assertFalse(c.manualFocus)
        assertFalse(c.aeLock)
        assertFalse(c.awbLock)
        assertEquals(WhiteBalance.AUTO, c.whiteBalance)
        // AF lock needs autofocus, not a capability flag: kept, the stream ignores it without a focuser.
        assertTrue(c.afLock)
        val noTungsten = ControlRanges(whiteBalances = setOf(WhiteBalance.AUTO, WhiteBalance.DAYLIGHT))
        assertEquals(WhiteBalance.AUTO, noTungsten.clamp(CameraControls(whiteBalance = WhiteBalance.TUNGSTEN), 30).whiteBalance)
        assertEquals(WhiteBalance.DAYLIGHT, noTungsten.clamp(CameraControls(whiteBalance = WhiteBalance.DAYLIGHT), 30).whiteBalance)
    }

    @Test
    fun `iso stops are the third-stop scale inside the range with its ends`() {
        val stops = ControlRanges(isoRange = 55..3000, exposureTimeRangeNs = 1L..2L).isoStops()
        assertEquals(55, stops.first())
        assertEquals(3000, stops.last())
        assertTrue(64 in stops && 100 in stops && 2500 in stops)
        assertEquals(stops.sorted(), stops)
    }

    @Test
    fun `shutter stops include the 180 degree shutter and stay within one frame`() {
        val stops24 = full.shutterStops(24)
        assertTrue(ControlMath.shutter180Ns(24) in stops24)
        assertTrue(stops24.all { it <= ControlMath.frameDurationNs(24) })
        assertEquals(stops24.sorted(), stops24)
        assertEquals(10_000L, stops24.first())
        assertTrue(ControlMath.shutter180Ns(60) in full.shutterStops(60))
    }

    @Test
    fun `nearest stop and display formats`() {
        val stops = listOf(100, 200, 400, 800)
        assertEquals(2, ControlRanges.nearestIndex(stops, 350.0))
        assertEquals("1/50", ControlRanges.formatShutter(20_000_000L))
        assertEquals("1/8000", ControlRanges.formatShutter(125_000L))
        assertEquals("∞", ControlRanges.formatFocus(0f))
        assertEquals(1f, full.ev(6), 1e-6f)
    }
}
