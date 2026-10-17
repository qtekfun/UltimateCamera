package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlMathTest {
    @Test
    fun `180 degree shutter is half a frame`() {
        assertEquals(20_000_000L, ControlMath.shutter180Ns(25))
        assertEquals(16_666_667L, ControlMath.shutter180Ns(30))
        assertEquals(8_333_333L, ControlMath.shutter180Ns(60))
        assertEquals("1/48", ControlRanges.formatShutter(ControlMath.shutter180Ns(24)))
        assertEquals("1/120", ControlRanges.formatShutter(ControlMath.shutter180Ns(60)))
        assertEquals(33_333_333L, ControlMath.frameDurationNs(30))
    }

    @Test
    fun `fit viewport letterboxes like the renderer`() {
        // 16:9 video in a 2400x1080 view: pillarbox of (2400-1920)/2 = 240 on each side.
        assertEquals(listOf(240, 0, 1920, 1080), ControlMath.fitViewport(2400, 1080, 3840, 2160).toList())
        // 16:9 video in a 4:3 view: letterbox top and bottom.
        assertEquals(listOf(0, 75, 1200, 675), ControlMath.fitViewport(1200, 825, 1920, 1080).toList())
    }

    @Test
    fun `preview touch maps into the picture and ignores the bars`() {
        val centre = ControlMath.previewToImage(1200f, 540f, 2400, 1080, 3840, 2160, rotation = 0)!!
        assertEquals(0.5f, centre.x, 1e-4f)
        assertEquals(0.5f, centre.y, 1e-4f)
        val corner = ControlMath.previewToImage(240f, 0f, 2400, 1080, 3840, 2160, rotation = 0)!!
        assertEquals(0f, corner.x, 1e-4f)
        assertEquals(0f, corner.y, 1e-4f)
        assertNull(ControlMath.previewToImage(100f, 540f, 2400, 1080, 3840, 2160, rotation = 0))
        assertNull(ControlMath.previewToImage(2350f, 540f, 2400, 1080, 3840, 2160, rotation = 0))
    }

    @Test
    fun `rotated preview flips the point`() {
        // Top-left quarter of the view is the bottom-right of the picture when the preview is turned 180°.
        val p = ControlMath.previewToImage(240f + 480f, 270f, 2400, 1080, 3840, 2160, rotation = 180)!!
        assertEquals(0.75f, p.x, 1e-4f)
        assertEquals(0.75f, p.y, 1e-4f)
        val (x, y) = ControlMath.imageToPreview(p, 2400, 1080, 3840, 2160, rotation = 180)
        assertEquals(720f, x, 1e-2f)
        assertEquals(270f, y, 1e-2f)
    }

    @Test
    fun `quarter turns fit the portrait picture and map back to sensor orientation`() {
        // Portrait 1080x2400 view, 16:9 video turned 90° clockwise: 1080x1920 picture from y = 240.
        assertNull(ControlMath.previewToImage(540f, 100f, 1080, 2400, 3840, 2160, rotation = 90))
        // The picture's top-left corner is at the screen's top-right after a clockwise quarter turn.
        val topLeft = ControlMath.previewToImage(1080f, 240f, 1080, 2400, 3840, 2160, rotation = 90)!!
        assertEquals(0f, topLeft.x, 1e-4f)
        assertEquals(0f, topLeft.y, 1e-4f)
        // ... and at the bottom-left after a counter-clockwise one (270).
        val ccw = ControlMath.previewToImage(0f, 2160f, 1080, 2400, 3840, 2160, rotation = 270)!!
        assertEquals(0f, ccw.x, 1e-4f)
        assertEquals(0f, ccw.y, 1e-4f)
        listOf(0, 90, 180, 270).forEach { rotation ->
            val p = ImagePoint(0.2f, 0.7f)
            val (x, y) = ControlMath.imageToPreview(p, 1080, 2400, 3840, 2160, rotation)
            val back = ControlMath.previewToImage(x, y, 1080, 2400, 3840, 2160, rotation)!!
            assertEquals("rotation $rotation", p.x, back.x, 1e-3f)
            assertEquals("rotation $rotation", p.y, back.y, 1e-3f)
        }
    }

    @Test
    fun `sensor region uses the centred 16 by 9 part of a 4 by 3 active array`() {
        val active = SensorRect(0, 0, 4000, 3000)
        // 16:9 stream of a 4000x3000 array: 4000x2250 starting at y = 375.
        val centre = ControlMath.imageToSensor(ImagePoint(0.5f, 0.5f), active, 3840, 2160, sizeFraction = 0.1f)
        assertEquals(SensorRect(2000 - 112, 1500 - 112, 225, 225).width, centre.width)
        assertEquals(2000, centre.left + centre.width / 2, 1)
        assertEquals(1500, centre.top + centre.height / 2, 1)
        val topLeft = ControlMath.imageToSensor(ImagePoint(0f, 0f), active, 3840, 2160, sizeFraction = 0.1f)
        assertEquals(0, topLeft.left)
        assertEquals(375 - 112, topLeft.top, 1)
    }

    @Test
    fun `sensor region stays inside the crop`() {
        val crop = SensorRect(100, 50, 3800, 2900)
        listOf(ImagePoint(0f, 0f), ImagePoint(1f, 1f), ImagePoint(1f, 0f), ImagePoint(0f, 1f)).forEach { p ->
            val r = ControlMath.imageToSensor(p, crop, 1920, 1080)
            assertTrue("$p -> $r", r.left >= crop.left && r.top >= crop.top)
            assertTrue("$p -> $r", r.left + r.width <= crop.left + crop.width && r.top + r.height <= crop.top + crop.height)
        }
    }

    @Test
    fun `planckian locus matches known white points`() {
        val (x65, y65) = KelvinMath.planckianXy(6504)
        assertEquals(0.3135, x65, 0.003) // Planckian 6504 K sits just off D65 (0.3127, 0.3290).
        assertEquals(0.3237, y65, 0.003)
        val (xa, ya) = KelvinMath.planckianXy(2856)
        assertEquals(0.4476, xa, 0.002) // Illuminant A.
        assertEquals(0.4074, ya, 0.002)
    }

    @Test
    fun `kelvin gains are neutral at the calibration white and warm light needs more blue`() {
        // A sensor that is plain XYZ normalised to the 5000 K black body: neutral gains there.
        val (x, y) = KelvinMath.planckianXy(5000)
        val white = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val matrix = floatArrayOf((1 / white[0]).toFloat(), 0f, 0f, 0f, 1f, 0f, 0f, 0f, (1 / white[2]).toFloat())
        val cal = ColorCalibration(5000, matrix)
        val neutral = KelvinMath.kelvinToGains(5000, cal, null)
        assertEquals(1f, neutral.red, 1e-3f)
        assertEquals(1f, neutral.blue, 1e-3f)
        val tungsten = KelvinMath.kelvinToGains(3200, cal, null)
        val shade = KelvinMath.kelvinToGains(7500, cal, null)
        assertTrue(tungsten.blue > neutral.blue && tungsten.red < neutral.red)
        assertTrue(shade.blue < neutral.blue && shade.red > neutral.red)
    }

    @Test
    fun `kelvin interpolates between the two reference illuminants`() {
        val a = ColorCalibration(2856, ColorCalibration.IDENTITY)
        val d65 = ColorCalibration(6504, floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 2f))
        // At each reference the gains are those of that matrix alone.
        assertEquals(KelvinMath.kelvinToGains(2856, a, null), KelvinMath.kelvinToGains(2856, a, d65))
        assertEquals(KelvinMath.kelvinToGains(6504, d65, null), KelvinMath.kelvinToGains(6504, a, d65))
        val mid = KelvinMath.kelvinToGains(4000, a, d65)
        assertTrue(mid.red in 0.25f..16f && mid.blue in 0.25f..16f)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) = assertTrue("$actual vs $expected", kotlin.math.abs(expected - actual) <= tolerance)
}
