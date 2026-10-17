package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoSizesTest {
    private val previews = listOf(
        PixelSize(3840, 2160),
        PixelSize(1920, 1080),
        PixelSize(1440, 1080),
        PixelSize(1280, 960),
        PixelSize(640, 480)
    )

    @Test
    fun picksTheLargestJpegAndTheLargestPreviewWithItsAspect() {
        val sizes = PhotoSizes.choose(listOf(PixelSize(1920, 1080), PixelSize(4032, 3024), PixelSize(4000, 2250)), previews)!!
        assertEquals(PixelSize(4032, 3024), sizes.jpeg)
        assertEquals(PixelSize(1440, 1080), sizes.preview)
    }

    @Test
    fun previewNeverExceedsTheWidthLimit() {
        val sizes = PhotoSizes.choose(listOf(PixelSize(4000, 2250)), previews)!!
        assertEquals(PixelSize(1920, 1080), sizes.preview)
    }

    @Test
    fun fallsBackToTheClosestAspect() {
        val sizes = PhotoSizes.choose(listOf(PixelSize(4000, 3000)), listOf(PixelSize(1920, 1080), PixelSize(1600, 1000)))!!
        assertEquals(PixelSize(1600, 1000), sizes.preview)
    }

    @Test
    fun noJpegNoPhoto() {
        assertNull(PhotoSizes.choose(emptyList(), previews))
        assertNull(PhotoSizes.choose(listOf(PixelSize(4000, 3000)), listOf(PixelSize(3840, 2160))))
    }
}
