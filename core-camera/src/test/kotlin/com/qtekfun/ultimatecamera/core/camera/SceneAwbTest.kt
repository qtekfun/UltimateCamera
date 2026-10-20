package com.qtekfun.ultimatecamera.core.camera

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAwbTest {
    private fun gains(red: Float, blue: Float) = RggbGains(red, 1f, 1f, blue)

    /** The OPPO CPH2841's own automatic gains for the owner's grey wall, with its picture nearly neutral. */
    private val oppo = gains(2.428f, 1.402f)

    @Test
    fun aNeutralPictureKeepsTheCamerasGains() {
        val a = SceneAwb()
        assertEquals(oppo, a.update(oppo, oppo, 1f, 1f))
        assertNull("no change, nothing to send", a.update(oppo, oppo, 1f, 1f))
    }

    @Test
    fun aCyanPictureGetsMoreRedGainUntilItIsNeutral() {
        // Regression (0.8.1 on the OPPO): the Kelvin locus put red at 2.32, the picture measured red/green 0.88 and a
        // temperature could not move off the locus, so it stayed cyan. The gains must close that gap by themselves.
        val a = SceneAwb()
        var current = gains(2.32f, 1.46f)
        repeat(30) {
            // A camera whose picture is neutral at the OPPO's own gains.
            val red = current.red / oppo.red
            val blue = current.blue / oppo.blue
            a.update(current, oppo, red, blue)?.let { current = it }
        }
        assertEquals(oppo.red, current.red, 0.02f)
        assertEquals(oppo.blue, current.blue, 0.02f)
    }

    @Test
    fun aBluePictureLowersTheBlueGainPartOfTheWay() {
        // The first report: a brown box made the camera's AWB turn the white wall blue.
        val a = SceneAwb()
        val next = requireNotNull(a.update(oppo, oppo, 0.85f, 1.2f))
        assertTrue(next.blue < oppo.blue && next.blue > oppo.blue / 1.2f)
        assertTrue(next.red > oppo.red && next.red < oppo.red / 0.85f)
    }

    @Test
    fun withoutACalibrationTheGainsStayNearTheCamerasOwn() {
        val a = SceneAwb()
        var current = oppo
        repeat(60) { a.update(current, oppo, 0.2f, 5f)?.let { current = it } }
        assertEquals(SceneAwb.ANCHOR_DEVIATION, ln(current.red / oppo.red).toDouble(), 0.01)
        assertEquals(-SceneAwb.ANCHOR_DEVIATION, ln(current.blue / oppo.blue).toDouble(), 0.01)
    }

    @Test
    fun aWrongCameraAwbDoesNotHoldTheGainsBack() {
        // Regression (D93 on the OPPO): with an orange notebook in view its AWB started at red 1.50, the grey cloth
        // needed 2.23 or more, and bounds around the camera's gains stopped red short (cyan wall). The sensor's
        // calibrated range must let it get there.
        val bounds = GainBounds(ln(1.2)..ln(3.5), ln(1.0)..ln(2.5))
        val a = SceneAwb(bounds)
        val camera = gains(1.497f, 2.045f)
        val needed = gains(2.45f, 1.62f)
        var current = camera
        repeat(40) { a.update(current, camera, current.red / needed.red, current.blue / needed.blue)?.let { current = it } }
        assertEquals(needed.red, current.red, 0.02f)
        assertEquals(needed.blue, current.blue, 0.02f)
        // And never past the bounds, whatever the measurement says.
        repeat(60) { a.update(current, camera, 0.1f, 9f)?.let { current = it } }
        assertEquals(3.5f, current.red, 0.03f)
        assertEquals(1.0f, current.blue, 0.01f)
    }

    @Test
    fun locusBoundsHoldTheCalibratedGainsWithRoomForTint() {
        val cal = ColorCalibration(6504, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        val b = SceneAwb.locusBounds(cal, null)
        for (k in listOf(2000, 3200, 5600, 10000)) {
            val g = KelvinMath.kelvinToGains(k, cal, null)
            assertTrue(ln(g.red.toDouble()) - SceneAwb.TINT_MARGIN >= b.logRed.start - 1e-6)
            assertTrue(ln(g.blue.toDouble()) + SceneAwb.TINT_MARGIN <= b.logBlue.endInclusive + 1e-6)
        }
    }

    @Test
    fun resetStartsAgainFromTheCamerasGains() {
        val a = SceneAwb()
        a.update(oppo, oppo, 0.9f, 1.1f)
        a.reset()
        assertNull(a.gains)
        assertEquals(oppo, a.update(oppo, oppo, 1f, 1f))
    }
}
