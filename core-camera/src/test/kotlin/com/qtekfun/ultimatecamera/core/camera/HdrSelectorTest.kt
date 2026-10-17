package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrSelectorTest {
    /** Pixel 8 class: Google's extensions (Night, Auto; HDR maybe not), no JPEG_R stream, manual sensor. */
    private val pixelLike = HdrSupport(extensions = setOf(HdrMethod.EXTENSION_NIGHT, HdrMethod.EXTENSION_AUTO), bracket = true)

    /** Pixel class that also lists JPEG_R. */
    private val pixelWithJpegR = pixelLike.copy(jpegR = true)

    /** OPPO class: the maker's HDR extension. */
    private val oppoLike = HdrSupport(extensions = setOf(HdrMethod.EXTENSION_HDR, HdrMethod.EXTENSION_NIGHT), bracket = true)

    /** Modest phone: no extensions, no JPEG_R, but YUV and AE compensation. */
    private val modest = HdrSupport(bracket = true)

    @Test
    fun autoPrefersTheMakersHdrExtension() {
        val plan = HdrSelector.plan(oppoLike, HdrChoice.Auto, gainMapEncoder = true)
        assertEquals(HdrMethod.EXTENSION_HDR, plan.method)
        assertFalse(plan.ultraHdr)
    }

    @Test
    fun extensionWithJpegROutputCarriesTheGainMap() {
        val plan = HdrSelector.plan(oppoLike.copy(extensionJpegR = setOf(HdrMethod.EXTENSION_HDR)), HdrChoice.Auto, gainMapEncoder = true)
        assertEquals(HdrPlan(HdrMethod.EXTENSION_HDR, ultraHdr = true), plan)
    }

    @Test
    fun withoutHdrExtensionAutoTakesJpegR() {
        assertEquals(HdrPlan(HdrMethod.ULTRA_HDR, ultraHdr = true), HdrSelector.plan(pixelWithJpegR, HdrChoice.Auto, gainMapEncoder = true))
    }

    @Test
    fun nightAndAutoExtensionsAreNotHdrSoAutoFusesWithAGainMap() {
        assertEquals(HdrPlan(HdrMethod.FUSION, ultraHdr = true), HdrSelector.plan(pixelLike, HdrChoice.Auto, gainMapEncoder = true))
        assertEquals(HdrPlan(HdrMethod.FUSION, ultraHdr = false), HdrSelector.plan(modest, HdrChoice.Auto, gainMapEncoder = false))
    }

    @Test
    fun nothingAvailableMeansOff() {
        assertEquals(HdrMethod.OFF, HdrSelector.plan(HdrSupport(), HdrChoice.Auto, gainMapEncoder = true).method)
        assertEquals(listOf(HdrMethod.OFF), HdrSelector.available(HdrSupport()))
    }

    @Test
    fun offIsOff() {
        assertEquals(HdrPlan(HdrMethod.OFF, ultraHdr = false), HdrSelector.plan(oppoLike, HdrChoice.Off, gainMapEncoder = true))
    }

    @Test
    fun manualMethodIsHonouredWhenOfferedElseAuto() {
        assertEquals(HdrMethod.EXTENSION_NIGHT, HdrSelector.plan(pixelLike, HdrChoice.Manual(HdrMethod.EXTENSION_NIGHT), true).method)
        assertEquals(HdrMethod.FUSION, HdrSelector.plan(pixelLike, HdrChoice.Manual(HdrMethod.EXTENSION_HDR), true).method)
    }

    @Test
    fun availableListsWhatThePhoneHasInDisplayOrder() {
        assertEquals(
            listOf(HdrMethod.EXTENSION_HDR, HdrMethod.EXTENSION_NIGHT, HdrMethod.FUSION, HdrMethod.OFF),
            HdrSelector.available(oppoLike)
        )
        assertEquals(listOf(HdrMethod.FUSION, HdrMethod.OFF), HdrSelector.available(modest))
    }

    @Test
    fun labelsForTheCatalogue() {
        val labels = pixelWithJpegR.copy(extensionJpegR = setOf(HdrMethod.EXTENSION_AUTO)).labels()
        assertEquals(listOf("extension_auto", "extension_night", "extension_auto+jpeg_r", "jpeg_r", "bracket"), labels)
        assertTrue(HdrSupport().labels().isEmpty())
    }
}
