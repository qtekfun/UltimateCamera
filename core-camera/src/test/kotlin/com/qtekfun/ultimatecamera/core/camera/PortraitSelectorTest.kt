package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitSelectorTest {
    private val main = LensOptics(focalMm = 6.9f, sensorWidthMm = 9.8f, sensorHeightMm = 7.4f) // ~24 mm eq.
    private val ultrawide = LensOptics(focalMm = 2.0f, sensorWidthMm = 5.6f, sensorHeightMm = 4.2f) // ~12 mm eq.
    private val tele = LensOptics(focalMm = 15f, sensorWidthMm = 5.6f, sensorHeightMm = 4.2f) // ~93 mm eq.

    /** Pixel 8 class: no DEPTH_OUTPUT, no bokeh extension, a logical back camera with ultrawide + main. */
    private val pixelLike = DepthSupport(
        stereo = PortraitSelector.stereoPair(
            LensFacing.BACK,
            listOf(PhysicalLens("2", LensFacing.BACK, main, yuv = true), PhysicalLens("3", LensFacing.BACK, ultrawide, yuv = true))
        )
    )

    /** OPPO class: the maker's portrait through EXTENSION_BOKEH, and a logical camera too. */
    private val oppoLike = pixelLike.copy(bokehExtension = true)

    /** A phone with a ToF / depth camera listing DEPTH16. */
    private val tofLike = oppoLike.copy(depth16 = listOf(PixelSize(640, 480)))

    /** Modest phone: one back camera, nothing. */
    private val modest = DepthSupport()

    @Test
    fun pixelLikeOffersStereoOnlyWithExperimentalOn() {
        assertNull(PortraitSelector.choose(pixelLike, experimental = false))
        assertEquals(emptyList<DepthSource>(), PortraitSelector.available(pixelLike, experimental = false))
        assertEquals(DepthSource.STEREO, PortraitSelector.choose(pixelLike, experimental = true))
    }

    @Test
    fun pixelLikeStereoPairIsMainPlusUltrawide() {
        val pair = assertNotNullPair(pixelLike.stereo)
        assertEquals("2", pair.main)
        assertEquals("3", pair.second)
        // The ultrawide's centre crop that matches the main's view: (2.0/5.6) / (6.9/9.8) ≈ 0.507.
        assertEquals(0.507f, pair.secondCrop, 0.01f)
    }

    @Test
    fun oppoLikeUsesTheMakersPortrait() {
        assertEquals(DepthSource.VENDOR_BOKEH, PortraitSelector.choose(oppoLike, experimental = false))
        assertEquals(DepthSource.VENDOR_BOKEH, PortraitSelector.choose(oppoLike, experimental = true))
        assertEquals(listOf(DepthSource.VENDOR_BOKEH, DepthSource.STEREO), PortraitSelector.available(oppoLike, experimental = true))
    }

    @Test
    fun aDepthCameraComesFirst() {
        assertEquals(DepthSource.DEPTH_CAMERA, PortraitSelector.choose(tofLike, experimental = false))
    }

    @Test
    fun modestPhoneGetsNoPortraitAtAll() {
        assertNull(PortraitSelector.choose(modest, experimental = true))
        assertTrue(PortraitSelector.available(modest, experimental = true).isEmpty())
        assertTrue(modest.labels().isEmpty())
    }

    @Test
    fun withTheSettingOffNothingIsProbedNorOffered() {
        var probed = 0
        val offered = PortraitSelector.offered(enabled = false, experimental = true) {
            probed++
            tofLike
        }
        assertTrue(offered.isEmpty())
        assertEquals(0, probed)
    }

    @Test
    fun withTheSettingOnTheSourcesAreOffered() {
        assertEquals(listOf(DepthSource.VENDOR_BOKEH), PortraitSelector.offered(enabled = true, experimental = false) { oppoLike })
        assertEquals(listOf(DepthSource.STEREO), PortraitSelector.offered(enabled = true, experimental = true) { pixelLike })
        assertTrue(PortraitSelector.offered(enabled = true, experimental = true) { modest }.isEmpty())
    }

    @Test
    fun labelsListEverySourceForDiagnostics() {
        assertEquals(listOf("depth16", "depth_point_cloud", "extension_bokeh", "stereo:2+3"), tofLike.copy(pointCloud = true).labels())
    }

    @Test
    fun stereoNeedsTwoUsableCamerasFacingTheSameWay() {
        assertNull(PortraitSelector.stereoPair(LensFacing.BACK, listOf(PhysicalLens("2", LensFacing.BACK, main, yuv = true))))
        assertNull(
            PortraitSelector.stereoPair(
                LensFacing.BACK,
                listOf(PhysicalLens("2", LensFacing.BACK, main, yuv = true), PhysicalLens("3", LensFacing.BACK, ultrawide, yuv = false))
            )
        )
        assertNull(
            PortraitSelector.stereoPair(
                LensFacing.BACK,
                listOf(PhysicalLens("2", LensFacing.BACK, main, yuv = true), PhysicalLens("4", LensFacing.FRONT, ultrawide, yuv = true))
            )
        )
        // Main + tele only: the tele sees less than the main, so no pair (the depth would not cover the photo).
        assertNull(
            PortraitSelector.stereoPair(
                LensFacing.BACK,
                listOf(PhysicalLens("2", LensFacing.BACK, main, yuv = true), PhysicalLens("5", LensFacing.BACK, tele, yuv = true))
            )
        )
    }

    @Test
    fun withThreeCamerasTheMainPairsWithTheUltrawide() {
        val pair = PortraitSelector.stereoPair(
            LensFacing.BACK,
            listOf(
                PhysicalLens("5", LensFacing.BACK, tele, yuv = true),
                PhysicalLens("3", LensFacing.BACK, ultrawide, yuv = true),
                PhysicalLens("2", LensFacing.BACK, main, yuv = true)
            )
        )
        assertEquals("2" to "3", pair?.let { it.main to it.second })
    }

    private fun assertNotNullPair(pair: StereoPair?): StereoPair {
        assertNotNull(pair)
        return requireNotNull(pair)
    }
}
