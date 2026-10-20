package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D97: an idle camera-role phone runs a 60 fps mode at 30 until REC. */
class StandbyRateTest {
    private val advertised = listOf(15..15, 15..30, 30..30, 24..24, 30..60, 60..60)

    @Test
    fun sixtyFpsModesIdleAtTheFixedThirtyRange() {
        assertEquals(30..30, StandbyRate.range(60, advertised))
        assertEquals(30..30, StandbyRate.range(120, advertised))
    }

    @Test
    fun onlyAdvertisedRangesEndingAtThirtyAreUsed() {
        assertEquals(15..30, StandbyRate.range(60, listOf(15..30, 30..60, 60..60)))
        assertNull(StandbyRate.range(60, listOf(15..15, 24..24, 60..60)))
    }

    @Test
    fun modesAtOrBelowThirtyAndVendorSessionsHaveNoStandby() {
        assertNull(StandbyRate.range(30, advertised))
        assertNull(StandbyRate.range(24, advertised))
        assertNull(StandbyRate.range(60, advertised, vendorSession = true))
    }

    @Test
    fun fullRateNeedsNinetyPercentOfTheMode() {
        assertFalse(StandbyRate.atFullRate(null, 60))
        assertFalse(StandbyRate.atFullRate(30.0, 60))
        assertFalse(StandbyRate.atFullRate(45.0, 60))
        assertTrue(StandbyRate.atFullRate(59.9, 60))
        assertTrue(StandbyRate.atFullRate(54.0, 60))
    }
}
