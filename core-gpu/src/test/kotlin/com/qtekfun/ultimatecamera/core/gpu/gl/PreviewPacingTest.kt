package com.qtekfun.ultimatecamera.core.gpu.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPacingTest {
    @Test
    fun fullRateDrawsEveryFrame() {
        (0 until 10).forEach { assertTrue(PreviewPacing.draws(it, 1)) }
        (0 until 10).forEach { assertTrue(PreviewPacing.draws(it, 0)) }
    }

    @Test
    fun halfRateDrawsEveryOtherFrame() {
        assertEquals(listOf(0, 2, 4, 6, 8), (0 until 10).filter { PreviewPacing.draws(it, 2) })
    }

    /** D97: a 60 fps mode is shown at 30 even while recording; the thermal thinning halves what is shown from there. */
    @Test
    fun previewIsCappedAtThirtyAndThermalThinningStacks() {
        assertEquals(2, PreviewPacing.everyNth(60, 1))
        assertEquals(1, PreviewPacing.everyNth(30, 1))
        assertEquals(1, PreviewPacing.everyNth(24, 1))
        assertEquals(4, PreviewPacing.everyNth(60, 2))
        assertEquals(2, PreviewPacing.everyNth(30, 2))
        assertEquals(30f, PreviewPacing.shownFps(60, PreviewPacing.everyNth(60, 1)))
        assertEquals(15f, PreviewPacing.shownFps(60, PreviewPacing.everyNth(60, 2)))
    }
}
