package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.gpu.style.StyleEngine
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStylerTest {
    private val width = 37
    private val height = 150 // two full strips of 64 rows and a partial one

    private fun image() = IntArray(width * height) { i -> (0xFF shl 24) or ((i * 7 and 0xFF) shl 16) or ((i * 3 and 0xFF) shl 8) or (i and 0xFF) }

    private fun styleInStrips(pixels: IntArray, params: StyleParams): List<Int> {
        val starts = mutableListOf<Int>()
        PhotoStyler.styleStrips(
            width,
            height,
            params,
            read = { strip, y, rows -> pixels.copyInto(strip, 0, y * width, (y + rows) * width) },
            write = { strip, y, rows ->
                starts += y
                strip.copyInto(pixels, y * width, 0, rows * width)
            }
        )
        return starts
    }

    @Test
    fun stripsCoverTheWholeImageExactlyLikeTheEngine() {
        val params = StyleParams(StylePreset.SOFT_FILM, tone = 0.3f, saturation = -0.2f)
        val pixels = image()
        val expected = image().also { StyleEngine.applyStyle(it, params) }
        assertEquals(listOf(0, 64, 128), styleInStrips(pixels, params))
        assertArrayEquals(expected, pixels)
    }

    @Test
    fun identityAndEmptyImagesAreNotTouched() {
        val pixels = image()
        assertEquals(emptyList<Int>(), styleInStrips(pixels, StyleParams.of(StylePreset.STANDARD)))
        assertArrayEquals(image(), pixels)
        PhotoStyler.styleStrips(0, 0, StyleParams.of(StylePreset.MONO), { _, _, _ -> error("no read") }, { _, _, _ -> error("no write") })
    }

    @Test
    fun theCopiedExifKeepsTimeCameraExposureOrientationAndLocation() {
        val tags = PhotoStyler.EXIF_TAGS
        assertEquals(tags.size, tags.toSet().size)
        for (tag in listOf("Orientation", "DateTimeOriginal", "Model", "ExposureTime", "FNumber", "GPSLatitude")) assertTrue(tag, tag in tags)
    }

    @Test
    fun previewSampleSizeKeepsTheLongSideAtOrAboveTheTarget() {
        // A 50 MP photo (8160 px) for a 1280 px preview decodes at 1/4 (2040 px), never below the target.
        assertEquals(4, PhotoStyler.sampleSize(8160, 1280))
        assertEquals(2, PhotoStyler.sampleSize(4000, 1280))
        assertEquals(1, PhotoStyler.sampleSize(2559, 1280))
        assertEquals(1, PhotoStyler.sampleSize(640, 1280))
        for (side in listOf(1280, 3000, 9000, 12_000)) {
            val sample = PhotoStyler.sampleSize(side, 1280)
            assertTrue(side / sample >= 1280 && side / (sample * 2) < 1280)
        }
    }
}
