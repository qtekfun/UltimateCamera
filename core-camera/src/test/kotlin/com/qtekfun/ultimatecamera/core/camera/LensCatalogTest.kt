package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensCatalogTest {
    /** Pixel 8 as `dumpsys media.camera` reports it: logical back 0 (physical 2, 4, 3), logical front 1 (physical 6, 5). */
    private val pixel8 = listOf(
        CameraLenses(
            id = "0",
            facing = LensFacing.BACK,
            optics = LensOptics(6.90f, 9.792f, 7.373f),
            physical = listOf(
                CameraLenses("2", LensFacing.BACK, LensOptics(6.90f, 9.792f, 7.373f)),
                CameraLenses("4", LensFacing.BACK, LensOptics(6.90f, 4.896f, 3.686f)),
                CameraLenses("3", LensFacing.BACK, LensOptics(1.95f, 5.04f, 3.77f))
            ),
            zoomRange = 0.549f..8.0f
        ),
        CameraLenses(
            id = "1",
            facing = LensFacing.FRONT,
            optics = LensOptics(2.74f, 4.197f, 2.987f),
            physical = listOf(
                CameraLenses("6", LensFacing.FRONT, LensOptics(2.74f, 4.197f, 2.987f)),
                CameraLenses("5", LensFacing.FRONT, LensOptics(2.74f, 4.197f, 2.987f))
            ),
            zoomRange = 0.896f..10f
        )
    )

    /** An OPPO-like phone with ultra-wide, main and a 3× tele as separate cameras of the list, no logical camera. */
    private val oppo = listOf(
        CameraLenses("0", LensFacing.BACK, LensOptics(6.0f, 9.6f, 7.2f), zoomRange = 1f..10f),
        CameraLenses("1", LensFacing.FRONT, LensOptics(3.2f, 4.6f, 3.4f), zoomRange = 1f..4f),
        CameraLenses("2", LensFacing.BACK, LensOptics(2.1f, 5.6f, 4.2f), zoomRange = 1f..2f),
        CameraLenses("3", LensFacing.BACK, LensOptics(9.6f, 5.1f, 3.8f), zoomRange = 1f..6f)
    )

    @Test
    fun pixel8OffersUltraWideMainAndTheCropByZoomRatioOnTheLogicalCamera() {
        val catalog = LensCatalog.build(pixel8)
        assertEquals(listOf("0.5", "1", "2"), catalog.back.map { it.label })
        assertEquals(listOf(LensKind.ULTRA_WIDE, LensKind.MAIN, LensKind.CROP), catalog.back.map { it.kind })
        assertEquals(setOf("0"), catalog.back.map { it.cameraId }.toSet())
        // The ultra-wide's ratio is its real magnification, inside the zoom range.
        assertEquals(0.55f, catalog.back[0].zoomRatio, 0.01f)
        assertEquals(2f, catalog.back[2].zoomRatio, 0.05f)
        assertEquals(LensStop(LensKind.FRONT, "1", 1f, 1f), catalog.front)
        assertEquals("0", catalog.mainId)
        assertEquals(LensKind.ULTRA_WIDE, catalog.kindOf("3"))
        assertEquals(LensKind.FRONT, catalog.kindOf("6"))
    }

    @Test
    fun oppoOpensSeparateLensesByIdAndAddsA2xCrop() {
        val catalog = LensCatalog.build(oppo)
        assertEquals(listOf("0.6", "1", "2", "3"), catalog.back.map { it.label })
        assertEquals(listOf("2", "0", "0", "3"), catalog.back.map { it.cameraId })
        assertEquals(listOf(LensKind.ULTRA_WIDE, LensKind.MAIN, LensKind.CROP, LensKind.TELE), catalog.back.map { it.kind })
        // Separate cameras open at ratio 1; their own factor scales the zoom shown while pinching.
        assertEquals(1f, catalog.back[3].zoomRatio)
        assertEquals(3f * 1.5f, catalog.displayZoom("3", 1.5f), 0.1f)
        assertEquals("1", catalog.front?.cameraId)
    }

    @Test
    fun theSelectedStopIsTheLargestNotAboveTheZoom() {
        val catalog = LensCatalog.build(pixel8)
        assertEquals("1", catalog.selected("0", 1.4f)?.label)
        assertEquals("2", catalog.selected("0", 2f)?.label)
        assertEquals("0.5", catalog.selected("0", 0.7f)?.label)
        assertNull(catalog.selected("1", 1f))
        assertEquals(emptyList<LensStop>(), catalog.stopsFor("1"))
    }

    @Test
    fun aPhoneWithoutZoomOrSizesStillHasItsMainLens() {
        val catalog = LensCatalog.build(listOf(CameraLenses("0", LensFacing.BACK, optics = null)))
        assertEquals(listOf("1"), catalog.back.map { it.label })
        assertNull(catalog.front)
    }

    @Test
    fun factorsAreLabelledLikeAStockCamera() {
        assertEquals("0.5", LensCatalog.formatFactor(0.55f))
        assertEquals("0.6", LensCatalog.formatFactor(0.6f))
        assertEquals("1", LensCatalog.formatFactor(1f))
        assertEquals("5", LensCatalog.formatFactor(4.97f))
        assertEquals("1.5", LensCatalog.formatFactor(1.5f))
    }

    @Test
    fun equivalentFocalLengthUsesTheDiagonal() {
        assertEquals(24.4f, LensOptics(6.90f, 9.792f, 7.373f).equivalentMm, 0.2f)
        assertEquals(0f, LensOptics(6.90f, 0f, 0f).equivalentMm)
    }
}
