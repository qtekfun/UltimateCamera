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
    private val height = 600 // two full strips of 256 rows and a partial one

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
        assertEquals(listOf(0, 256, 512), styleInStrips(pixels, params))
        assertArrayEquals(expected, pixels)
    }

    @Test
    fun theCpuFallbackFinishesFromTheRowTheGpuReached() {
        val params = StyleParams.of(StylePreset.VIBRANT)
        val pixels = image()
        val expected = image().also { StyleEngine.applyStyle(it, params) }
        val starts = mutableListOf<Int>()
        PhotoStyler.styleOnCpu(width, height, params, { strip, y, rows -> pixels.copyInto(strip, 0, y * width, (y + rows) * width) }, { strip, y, rows ->
            starts += y
            strip.copyInto(pixels, y * width, 0, rows * width)
        }, from = 300)
        assertEquals(listOf(300, 556), starts)
        assertArrayEquals(image().copyOfRange(0, 300 * width), pixels.copyOfRange(0, 300 * width))
        assertArrayEquals(expected.copyOfRange(300 * width, pixels.size), pixels.copyOfRange(300 * width, pixels.size))
        PhotoStyler.styleOnCpu(width, height, params, { _, _, _ -> error("no read") }, { _, _, _ -> error("no write") }, from = height)
    }

    @Test
    fun aRowsWrittenWriterRemembersHowFarItGot() {
        val seen = mutableListOf<Int>()
        val written = PhotoFinisher.RowsWritten { _, y, _ -> seen += y }
        assertEquals(0, written.done)
        written(IntArray(4), 0, 256)
        written(IntArray(4), 256, 44)
        assertEquals(300, written.done)
        assertEquals(listOf(0, 256), seen)
    }

    @Test
    fun onlyLargeImagesTryTheGpu() {
        assertTrue(PhotoGpu.worth(4000, 3000) || !PhotoGpu.health.gpuUsable)
        assertTrue(!PhotoGpu.worth(640, 480))
        assertEquals("cpu", PhotoGpu.attempt("test", tryGpu = false, gpu = { error("not tried") }) {})
        var ran = ""
        assertEquals("gpu", PhotoGpu.attempt("test", tryGpu = true, gpu = { ran = "gpu" }) { ran = "cpu" })
        assertEquals("gpu", ran)
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
