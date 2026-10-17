package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.gpu.portrait.CpuPortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.FallbackPortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.GpuPortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.KernelHealth
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edit after capture on a phone (D88), synthetic data only: a striped "sharp" photo (subject stripes in the left half
 * at 1 D, background stripes on the right at 0.2 D, holes in the depth), edited to f/1.4 focused on the subject. The
 * subject must stay sharp, the background must lose its stripes, the edit keeps the original's EXIF orientation and
 * carries the original's style; then focused on the background, the reverse. GPU kernels with the CPU fallback, as in
 * the app; the kernels used and the time go to logcat (`UcPortrait`).
 * `./gradlew :core-recording:connectedDebugAndroidTest`
 * `-Pandroid.testInstrumentationRunnerArguments.class=com.qtekfun.ultimatecamera.core.recording.PortraitEditDeviceTest`
 */
@RunWith(AndroidJUnit4::class)
class PortraitEditDeviceTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "portrait-edit-test").apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val w = 1200
    private val h = 800

    private fun stripes(): Bitmap {
        val pixels = IntArray(w * h) { i -> if ((i % w / 6) % 2 == 0) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun depth(): Plane = Plane(
        120,
        80,
        FloatArray(120 * 80) { i ->
            val x = i % 120
            val y = i / 120
            if ((x * 7 + y * 13) % 10 < 2) {
                Bokeh.INVALID
            } else if (x < 60) {
                1f
            } else {
                0.2f
            }
        }
    )

    /** Mean absolute difference between neighbouring columns: high for sharp stripes, low once blurred. */
    private fun contrast(bitmap: Bitmap, x0: Int, x1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in h / 4 until 3 * h / 4 step 8) {
            for (x in x0 until x1) {
                sum += Math.abs((bitmap.getPixel(x + 1, y) and 0xFF) - (bitmap.getPixel(x, y) and 0xFF))
                n++
            }
        }
        return sum / n
    }

    @Test
    fun anEditRefocusesAndKeepsTheOriginalsMetadata() {
        val sharp = File(dir, "sharp.jpg")
        sharp.outputStream().use { stripes().compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val original = File(dir, "photo.jpg")
        val warm = StyleParams.of(StylePreset.WARM)
        File(dir, "plain.jpg").let { plain ->
            sharp.copyTo(plain, overwrite = true)
            ExifInterface(plain.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_MODEL, "synthetic")
                saveAttributes()
            }
            plain.inputStream().use { input -> original.outputStream().use { StyleXmp.insert(input, it, warm) } }
        }
        val health = KernelHealth()
        val editor = PortraitEditor(dir) { FallbackPortraitKernels(health, ::GpuPortraitKernels) }
        val started = System.nanoTime()
        val out = ByteArrayOutputStream()
        editor.save(sharp, { original.inputStream() }, depth(), PortraitLook(1.4f, 0.25f, 0.5f, keepEdit = false), warm, null, 95, out, null)
        Log.i("UcPortrait", "edit ${w}x$h in ${(System.nanoTime() - started) / 1_000_000} ms, gpu failure: ${health.failure}")
        val bytes = out.toByteArray()
        val edited = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(w, edited.width)
        val subject = contrast(edited, 100, 500)
        val background = contrast(edited, 700, 1100)
        assertTrue("subject $subject sharp, background $background blurred", subject > 4 * background)
        val file = File(dir, "edited.jpg").apply { writeBytes(bytes) }
        val exif = ExifInterface(file.path)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("synthetic", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(warm, file.inputStream().use { StyleXmp.read(it) })

        val reverse = ByteArrayOutputStream()
        PortraitEditor(dir) {
            CpuPortraitKernels
        }.save(sharp, { original.inputStream() }, depth(), PortraitLook(1.4f, 0.75f, 0.5f), null, null, 95, reverse, null)
        val back = reverse.toByteArray().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        assertTrue("focused on the background", contrast(back, 700, 1100) > 4 * contrast(back, 100, 500))
    }
}
