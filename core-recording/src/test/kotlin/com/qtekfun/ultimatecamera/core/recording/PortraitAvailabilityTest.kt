package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.camera.DepthSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitAvailabilityTest {
    @Test
    fun aSourceThatGaveNoDepthIsNotOfferedAgainOnThatCamera() {
        // Pixel 8 report: the stereo session opened but the physical streams gave nothing.
        val availability = PortraitAvailability()
        val sources = listOf(DepthSource.VENDOR_BOKEH, DepthSource.STEREO)
        assertEquals(sources, availability.usable("0", sources))
        availability.markFailed("0", DepthSource.STEREO)
        assertEquals(listOf(DepthSource.VENDOR_BOKEH), availability.usable("0", sources))
        assertTrue(availability.failed("0", DepthSource.STEREO))
        // Other cameras and other sources are untouched.
        assertEquals(sources, availability.usable("1", sources))
        assertFalse(availability.failed("0", DepthSource.VENDOR_BOKEH))
    }

    @Test
    fun withTheOnlySourceGoneNothingIsOffered() {
        val availability = PortraitAvailability()
        availability.markFailed("0", DepthSource.STEREO)
        assertTrue(availability.usable("0", listOf(DepthSource.STEREO)).isEmpty())
    }

    @Test
    fun stageTimerReportsEachStage() {
        var t = 0L
        val timer = StageTimer { t }
        t = 5_000_000L
        timer.mark("decode")
        t = 12_000_000L
        timer.mark("blur")
        assertEquals(listOf("decode" to 5L, "blur" to 7L), timer.millis)
        assertEquals(12L, timer.totalMillis)
        assertEquals("decode 5 ms, blur 7 ms, total 12 ms", timer.toString())
    }
}
