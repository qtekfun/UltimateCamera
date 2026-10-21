package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewAreaTest {
    @Test
    fun `the video never sits under REC's column or the bottom bar`() {
        // Seen in landscape: the right part of the 16:9 frame was under the REC column, so it could not be framed.
        assertEquals(PreviewArea(end = 140.dp), PreviewArea.of(portrait = false, bar = 200.dp, side = 140.dp, fallbackSide = 132.dp))
        assertEquals(PreviewArea(end = 132.dp), PreviewArea.of(portrait = false, bar = null, side = null, fallbackSide = 132.dp))
        assertEquals(PreviewArea(bottom = 214.dp), PreviewArea.of(portrait = true, bar = 214.dp, side = 140.dp, fallbackSide = 132.dp))
        assertEquals(PreviewArea.FULL, PreviewArea.of(portrait = true, bar = null, side = null, fallbackSide = 132.dp))
    }
}
