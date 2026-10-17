package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.camera.PhotoSizes
import com.qtekfun.ultimatecamera.core.camera.PixelSize
import com.qtekfun.ultimatecamera.core.recording.PhotoController
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitUiTest {
    private val sizes = PhotoSizes(PixelSize(4000, 3000), PixelSize(1440, 1080))

    @Test
    fun chipHiddenWithTheSettingOffBecauseNothingIsOffered() {
        // With Settings › Photo › Portrait off the controller probes nothing, so the camera reports no sources.
        assertFalse(PortraitUi.chipShown(PhotoState.Ready(sizes)))
        assertFalse(PortraitUi.chipShown(PhotoState.Closed))
    }

    @Test
    fun chipShownWithTheSettingOnAndASource() {
        assertTrue(PortraitUi.chipShown(PhotoState.Ready(sizes, portraitSources = listOf(DepthSource.VENDOR_BOKEH))))
    }

    @Test
    fun apertureStartsAtF28AndZeroIsOff() {
        assertEquals(2.8f, PortraitUi().fNumber, 0f)
        assertEquals(0f, PortraitUi(aperture = 0).fNumber, 0f)
        assertEquals(16f, PortraitUi(aperture = 8).fNumber, 0f)
        assertFalse(PortraitUi().on)
    }

    @Test
    fun depthMapIsSavedNextToThePhoto() {
        assertEquals("UC_20261017_101500_CAM0_P007_depth.png", PhotoController.depthName("UC_20261017_101500_CAM0_P007.jpg"))
    }
}
