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
}
