package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.camera.PhotoSizes
import com.qtekfun.ultimatecamera.core.camera.PixelSize
import com.qtekfun.ultimatecamera.core.recording.PhotoController
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import com.qtekfun.ultimatecamera.core.recording.PortraitLook
import com.qtekfun.ultimatecamera.core.recording.PortraitOptions
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun optionsFollowTheSettingsAndAreOffWithoutThem(): Unit = runBlocking {
        assertEquals(PortraitOptions(enabled = false, experimental = false, on = true), PortraitUi(on = true).options(null, null))
        assertEquals(PortraitOptions(enabled = true, experimental = true, on = false), PortraitUi().options(flowOf(true), flowOf(true)))
        assertEquals(PortraitOptions(enabled = false, experimental = false, on = false), PortraitUi().options(flowOf(false), flowOf(false)))
    }

    @Test
    fun onlyTurningPortraitOnOrOffReopensTheCamera() {
        assertTrue(PortraitUi().reopens(PortraitUi(on = true)))
        assertFalse(PortraitUi(on = true).reopens(PortraitUi(on = true, aperture = 1, saveDepth = true)))
    }

    @Test
    fun theSliderIsForOurOwnBlurOnly() {
        val stereo = PhotoState.Ready(sizes, portrait = DepthSource.STEREO, portraitSources = listOf(DepthSource.STEREO))
        val maker = PhotoState.Ready(sizes, portrait = DepthSource.VENDOR_BOKEH, portraitSources = listOf(DepthSource.VENDOR_BOKEH))
        val fellBack = PhotoState.Ready(sizes, portrait = null, portraitSources = listOf(DepthSource.STEREO))
        assertTrue(PortraitUi(on = true).sliderShown(stereo))
        assertFalse(PortraitUi(on = true).sliderShown(maker))
        assertFalse(PortraitUi(on = true).sliderShown(fellBack))
        assertFalse(PortraitUi(on = false).sliderShown(stereo))
        assertEquals(DepthSource.STEREO, PortraitUi(on = true).activeSource(stereo))
        assertNull(PortraitUi(on = true).activeSource(fellBack))
        assertNull(PortraitUi(on = false).activeSource(stereo))
    }

    @Test
    fun aFailedSourceKeepsTheChipToSayPortraitIsNotAvailable() {
        // Pixel 8: the stereo source gave no depth, so it is dropped and the shots are normal photos.
        val failed = PhotoState.Ready(sizes, portrait = null, portraitSources = emptyList(), portraitUnavailable = true)
        assertTrue(PortraitUi.chipShown(failed))
        assertTrue(PortraitUi(on = true).unavailable(failed))
        assertFalse(PortraitUi(on = false).unavailable(failed))
        assertFalse(PortraitUi(on = true).sliderShown(failed))
        assertNull(PortraitUi(on = true).activeSource(failed))
    }

    @Test
    fun apertureTextAndSourceNames() {
        assertNull(PortraitUi.apertureText(0))
        assertEquals("1.4", PortraitUi.apertureText(1))
        assertEquals("2.8", PortraitUi.apertureText(3))
        assertEquals("16", PortraitUi.apertureText(8))
        assertEquals(R.string.photo_portrait_source_depth, PortraitUi.sourceName(DepthSource.DEPTH_CAMERA))
        assertEquals(R.string.photo_portrait_source_maker, PortraitUi.sourceName(DepthSource.VENDOR_BOKEH))
        assertEquals(R.string.photo_portrait_source_stereo, PortraitUi.sourceName(DepthSource.STEREO))
        assertEquals(PortraitLook(2.8f, saveDepth = true), PortraitUi(saveDepth = true).look)
    }

    @Test
    fun depthMapIsSavedNextToThePhoto() {
        assertEquals("UC_20261017_101500_CAM0_P007_depth.png", PhotoController.depthName("UC_20261017_101500_CAM0_P007.jpg"))
    }
}
