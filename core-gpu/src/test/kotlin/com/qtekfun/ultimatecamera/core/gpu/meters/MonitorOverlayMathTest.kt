package com.qtekfun.ultimatecamera.core.gpu.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorOverlayMathTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun single(pixel: Int, settings: MonitorOverlaySettings, x: Int = 0, y: Int = 0, size: Int = 8, frame: Long = 0): Int {
        val image = IntArray(size * size) { pixel }
        return MonitorOverlayMath.render(image, size, size, size, size, settings, frame)[y * size + x]
    }

    @Test
    fun passthroughIsLossless() {
        val chart = MeterTestCharts.overlayChart()
        val w = MeterTestCharts.OVERLAY_WIDTH
        val h = MeterTestCharts.OVERLAY_HEIGHT
        assertTrue(chart.contentEquals(MonitorOverlayMath.render(chart, w, h, w, h, MonitorOverlaySettings())))
    }

    @Test
    fun falseColorFollowsTheArriLikeBands() {
        val s = MeterReferenceSettings.falseColor
        val bands = FalseColorSettings.ARRI_LIKE
        assertEquals("0 % is black clip", bands[0].color, single(grey(0), s))
        assertEquals("8/255 = 3.1 % is near black", bands[1].color, single(grey(8), s))
        assertEquals("102/255 = 40 % is 18 % grey", bands[2].color, single(grey(102), s))
        assertEquals("138/255 = 54 % is one stop over", bands[3].color, single(grey(138), s))
        assertEquals("250/255 = 98 % is near white", bands[4].color, single(grey(250), s))
        assertEquals("100 % is white clip", bands[5].color, single(grey(255), s))
        assertEquals("other levels show their luma in grey", grey(180), single(grey(180), s))
    }

    @Test
    fun zebraStripesAreDiagonalAndMoveWhenAnimated() {
        val s = MeterReferenceSettings.zebras
        val black = 0xFF000000.toInt()
        assertEquals("stripe at (0,0)", black, single(grey(250), s, 0, 0))
        assertEquals("gap at (4,0)", grey(250), single(grey(250), s, 4, 0))
        assertEquals("diagonal: (3,1) is a gap like (4,0)", grey(250), single(grey(250), s, 3, 1))
        assertEquals("one frame later the stripe moved one pixel", grey(250), single(grey(250), s, 3, 0, frame = 1))
        assertEquals("secondary band uses its colour", 0xFFFFFFFF.toInt(), single(grey(178), s, 0, 7))
        assertEquals("levels outside both bands are untouched", grey(128), single(grey(128), s))
    }

    @Test
    fun peakingFiresOnSharpEdgesOnly() {
        val w = MeterTestCharts.OVERLAY_WIDTH
        val h = MeterTestCharts.OVERLAY_HEIGHT
        val out = MonitorOverlayMath.render(MeterTestCharts.overlayChart(), w, h, w, h, MeterReferenceSettings.peaking)
        val red = MeterReferenceSettings.peaking.peaking.color
        assertEquals("checker edge between x=7 and x=8", red, out[50 * w + 8])
        assertNotEquals("inside a checker square", red, out[50 * w + 4])
        for (x in w / 2 + 2 until w - 1) assertNotEquals("smooth gradient at x=$x", red, out[50 * w + x])
    }

    @Test
    fun desqueezeLetterboxesTheWiderImage() {
        assertEquals(PixelRect(0, 270, 1920, 540), MonitorGeometry.contentRect(1920, 1080, 1920, 1080, Desqueeze.X2))
        assertEquals(PixelRect(0, 134, 1920, 812), MonitorGeometry.contentRect(1920, 1080, 1920, 1080, Desqueeze.X1_33))
        assertEquals(PixelRect(0, 0, 1920, 1080), MonitorGeometry.contentRect(1920, 1080, 1920, 1080, Desqueeze.NONE))
        // A 4:3 anamorphic source at 1.33x is about 16:9 and fills the height.
        assertEquals(PixelRect(2, 0, 1915, 1080), MonitorGeometry.contentRect(1920, 1080, 1440, 1080, Desqueeze.X1_33))
        // A 2x squeeze of 4:3 is 2.67:1: pillarbox becomes letterbox.
        assertEquals(PixelRect(0, 180, 1920, 720), MonitorGeometry.contentRect(1920, 1080, 1440, 1080, Desqueeze.X2))
    }

    @Test
    fun desqueezedOutputIsBlackOutsideTheContent() {
        val w = MeterTestCharts.OVERLAY_WIDTH
        val h = MeterTestCharts.OVERLAY_HEIGHT
        val out = MonitorOverlayMath.render(MeterTestCharts.overlayChart(), w, h, w, h, MonitorOverlaySettings(desqueeze = Desqueeze.X2))
        val content = MonitorGeometry.contentRect(w, h, w, h, Desqueeze.X2)
        assertEquals(0xFF000000.toInt(), out[0])
        assertEquals(0xFF000000.toInt(), out[(content.y - 1) * w])
        assertEquals("top band (grey ramp) shows its last level", grey(255), out[(content.top - 1) * w + w - 1])
    }

    @Test
    fun stripePhaseWrapsAtTwoStripes() {
        val z = ZebraSettings(stripeWidthPx = 4, animated = true, speedPxPerFrame = 3)
        assertEquals(0, MonitorOverlayMath.stripePhase(z, 0))
        assertEquals(3, MonitorOverlayMath.stripePhase(z, 1))
        assertEquals(1, MonitorOverlayMath.stripePhase(z, 3))
        assertEquals(0, MonitorOverlayMath.stripePhase(z.copy(animated = false), 7))
    }

    @Test
    fun marginsFlagOnlyNearThresholdDecisions() {
        val w = MeterTestCharts.OVERLAY_WIDTH
        val h = MeterTestCharts.OVERLAY_HEIGHT
        val margins = FloatArray(w * h)
        MonitorOverlayMath.render(MeterTestCharts.overlayChart(), w, h, w, h, MeterReferenceSettings.all, 0, margins)
        val ambiguous = margins.count { it < MonitorOverlayMath.AMBIGUOUS }
        assertTrue("$ambiguous ambiguous pixels", ambiguous < w * h / 100)
    }
}
