package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PortraitBarInsetTest {
    @Test
    fun theMeasuredBarDecidesNotTheFixedGuess() {
        // Regression (OPPO CPH2841, portrait): the bottom bar was taller than the fixed 156 dp and covered the manual
        // controls; they now sit above its measured height, minus the navigation bar it pads itself.
        assertEquals(190.dp, portraitBarInset(214.dp, 24.dp))
        assertEquals(PORTRAIT_BAR, portraitBarInset(null, 24.dp))
        assertEquals(0.dp, portraitBarInset(10.dp, 24.dp))
    }
}
