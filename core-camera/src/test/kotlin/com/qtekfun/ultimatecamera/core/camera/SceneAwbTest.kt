package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAwbTest {
    /** A camera-like locus: warmer light needs more blue and less red gain. */
    private fun gainsAt(kelvin: Int) = RggbGains(kelvin / 5000f, 1f, 1f, 5000f / kelvin)

    private fun awb() = SceneAwb(2000..10000, ::gainsAt)

    @Test
    fun aNeutralPictureKeepsTheTemperatureOfTheCurrentGains() {
        val a = awb()
        assertEquals(5000, a.update(gainsAt(5000), 1f, 1f))
        assertNull("no change, nothing to send", a.update(gainsAt(5000), 1f, 1f))
    }

    @Test
    fun aBluePictureMovesTowardsLessBlueGainAndAWarmOneTheOtherWay() {
        // Regression: the camera's AWB left a white wall blue behind a brown box; the blue must be taken out.
        val cool = awb()
        val first = requireNotNull(cool.update(gainsAt(5000), 0.85f, 1.2f))
        assertTrue("less blue gain is a higher temperature on this locus", first > 5000)
        assertTrue(gainsAt(first).blue < gainsAt(5000).blue)
        val warm = awb()
        assertTrue(requireNotNull(warm.update(gainsAt(5000), 1.2f, 0.85f)) < 5000)
    }

    @Test
    fun laterMeasurementsMovePartOfTheWayAndSmallOnesAreIgnored() {
        val a = awb()
        a.update(gainsAt(5000), 1f, 1f)
        // The whole way would be 2500 K (200 mireds → 400): about a third of it is taken.
        val next = requireNotNull(a.update(gainsAt(5000), 2f, 0.5f))
        assertTrue(next in 3600..4000)
        assertNull("inside the dead band", a.update(gainsAt(next), 1.003f, 0.997f))
        assertEquals(next, a.kelvin)
    }

    @Test
    fun resetStartsAgainFromTheCamerasGains() {
        val a = awb()
        a.update(gainsAt(5000), 1f, 1f)
        a.reset()
        assertNull(a.kelvin)
        assertEquals(3000, a.update(gainsAt(3000), 1f, 1f))
    }
}
