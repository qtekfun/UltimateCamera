package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortraitAfTest {
    private val autofocus = ControlRanges(minFocusDiopters = 10f, maxAfRegions = 1, maxAeRegions = 1, activeArray = SensorRect(0, 0, 4000, 3000))
    private val still = PixelSize(4000, 3000)

    @Test
    fun aTapPutsTheAfAndAeRegionsOnThePointAndScansAgain() {
        val plan = PortraitAf.plan(previous = null, next = ImagePoint(0.25f, 0.5f), ranges = autofocus, still = still)
        // A 450-pixel square (15 % of the short side) centred on (1000, 1500) of the active array.
        val expected = SensorRect(1000 - 225, 1500 - 225, 450, 450)
        assertEquals(expected, plan.afRegion)
        assertEquals(expected, plan.aeRegion)
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, plan.trigger)
    }

    @Test
    fun theSamePointAgainKeepsTheRegionsWithoutANewTrigger() {
        val point = ImagePoint(0.7f, 0.2f)
        val plan = PortraitAf.plan(point, point, autofocus, still)
        assertEquals(PortraitAf.plan(null, point, autofocus, still).afRegion, plan.afRegion)
        assertNull(plan.trigger)
    }

    @Test
    fun clearingThePointRemovesTheRegionsAndScansTheWholeFrame() {
        val plan = PortraitAf.plan(ImagePoint(0.7f, 0.2f), null, autofocus, still)
        assertNull(plan.afRegion)
        assertNull(plan.aeRegion)
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, plan.trigger)
    }

    @Test
    fun aFixedFocusLensGetsNoAfRegionNorTriggerButStillMeters() {
        val fixed = autofocus.copy(minFocusDiopters = 0f)
        val plan = PortraitAf.plan(null, ImagePoint(0.5f, 0.5f), fixed, still)
        assertNull(plan.afRegion)
        assertNull(plan.trigger)
        assertEquals(SensorRect(2000 - 225, 1500 - 225, 450, 450), plan.aeRegion)
        val noRegions = autofocus.copy(maxAfRegions = 0, maxAeRegions = 0)
        val bare = PortraitAf.plan(null, ImagePoint(0.5f, 0.5f), noRegions, still)
        assertNull(bare.afRegion)
        assertNull(bare.aeRegion)
    }

    @Test
    fun aWideStillMapsIntoItsCropOfTheSensorAndPointsAreClamped() {
        // 16:9 stills on a 4:3 sensor: the picture is the middle 2250 rows, so y = 0.5 is row 1500 and x = 1 the right edge.
        val wide = PixelSize(3840, 2160)
        val plan = PortraitAf.plan(null, ImagePoint(1.4f, 0.5f), autofocus, wide)
        val r = requireNotNull(plan.afRegion)
        assertEquals(4000, r.left + r.width)
        assertEquals(1500f, r.top + r.height / 2f, 1f)
    }
}
