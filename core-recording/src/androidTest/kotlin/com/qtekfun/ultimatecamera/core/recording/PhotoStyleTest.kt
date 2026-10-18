package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatecamera.core.gpu.style.StyleEngine
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Styles on a real phone (D79): a photo-sized chart styled in strips must match the pure engine pixel for pixel, a
 * styled JPEG must keep its size and EXIF and carry the style in its XMP, and a 12 MP photo must be styled in under
 * 0.5 s on the GPU within one level of the CPU engine (D90; the times are logged under UCSTYLE). The lead runs it on the Pixel 8:
 * `./gradlew :core-recording:connectedDebugAndroidTest`
 * `-Pandroid.testInstrumentationRunnerArguments.class=com.qtekfun.ultimatecamera.core.recording.PhotoStyleTest`.
 */
@RunWith(AndroidJUnit4::class)
class PhotoStyleTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "style-test").apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun chart(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val r = x * 255 / (width - 1)
            val g = y * 255 / (height - 1)
            val b = (x + y) * 255 / (width + height - 2)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    @Test
    fun bitmapStripsMatchTheEngine() {
        for (preset in StylePreset.entries) {
            val params = StyleParams(preset, tone = 0.4f, warmth = -0.3f, saturation = 0.5f, intensity = 0.9f)
            val bitmap = chart(640, 427)
            val expected = IntArray(640 * 427).also { bitmap.getPixels(it, 0, 640, 0, 0, 640, 427) }
            StyleEngine.applyStyle(expected, params)
            PhotoStyler.applyStyle(bitmap, params)
            val got = IntArray(640 * 427).also { bitmap.getPixels(it, 0, 640, 0, 0, 640, 427) }
            assertArrayEquals("$preset", expected, got)
        }
    }

    @Test
    fun styledJpegKeepsSizeAndExifAndCarriesTheStyle() {
        val source = File(dir, "source.jpg")
        source.outputStream().use { chart(1200, 900).compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(source.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_MODEL, "chart")
            saveAttributes()
        }
        val params = StyleParams(StylePreset.SOFT_FILM, tone = 0.2f, intensity = 0.7f)
        val out = File(dir, "styled.jpg")
        PhotoStyler.styleJpeg(source, out, params, quality = 95, base = "source_base.jpg")
        assertEquals(params, out.inputStream().buffered().use { StyleXmp.read(it) })
        val exif = ExifInterface(out.path)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("chart", exif.getAttribute(ExifInterface.TAG_MODEL))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, bounds)
        assertEquals(1200, bounds.outWidth)
        assertEquals(900, bounds.outHeight)
    }

    @Test
    fun twelveMegapixelsAreStyledOnTheGpuWithinOneLevel() {
        val bitmap = chart(4000, 3000)
        val params = StyleParams.of(StylePreset.VIBRANT)
        val start = SystemClock.elapsedRealtime()
        PhotoStyler.applyStyle(bitmap, params)
        val ms = SystemClock.elapsedRealtime() - start
        Log.i("UCSTYLE", "12MP style: $ms ms (gpu usable ${PhotoGpu.health.gpuUsable}, ${PhotoGpu.health.failure})")
        // D90: the GPU pass against the CPU engine, and the CPU fallback (all cores) timed too.
        val expected = chart(4000, 3000)
        val cpuStart = SystemClock.elapsedRealtime()
        PhotoStyler.applyStyle(expected, params, gpu = false)
        Log.i("UCSTYLE", "12MP style on the CPU (all cores): ${SystemClock.elapsedRealtime() - cpuStart} ms")
        val a = IntArray(4000)
        val b = IntArray(4000)
        var max = 0
        for (y in 0 until 3000) {
            bitmap.getPixels(a, 0, 4000, 0, y, 4000, 1)
            expected.getPixels(b, 0, 4000, 0, y, 4000, 1)
            for (x in 0 until 4000) {
                for (shift in intArrayOf(0, 8, 16, 24)) {
                    max =
                        maxOf(max, Math.abs(((a[x] shr shift) and 0xFF) - ((b[x] shr shift) and 0xFF)))
                }
            }
        }
        Log.i("UCSTYLE", "12MP style GPU vs CPU: max $max levels")
        assertTrue("GPU vs CPU: $max levels", max <= 1)
        assertTrue("12 MP took $ms ms", ms < 500)
    }
}
