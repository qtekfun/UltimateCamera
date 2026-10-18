package com.qtekfun.ultimatecamera.core.recording

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qtekfun.ultimatecamera.core.camera.StillCaptureStream
import com.qtekfun.ultimatecamera.core.gpu.hdr.LumaStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real camera (point it at an ordinary scene): one plain JPEG, then the base rendering (D74). The rendered photo
 * must have blacks no higher and more spread than the camera's JPEG (the owner's washed-out complaint), without new
 * clipping, and the render of a 12 MP photo must take under 1 s (GPU passes, D90) and match the CPU reference within
 * one 8-bit level (all but 1 in 100 000 channel values). Logs `RENDER_STATS` and `RENDER_GPU_VS_CPU` (tag UcPhoto).
 */
@RunWith(AndroidJUnit4::class)
class PhotoRenderDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val thread = HandlerThread("render-test").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        texture = SurfaceTexture(0).apply { detachFromGLContext() }
        surface = Surface(texture)
    }

    @After
    fun tearDown() {
        surface.release()
        texture.release()
        thread.quitSafely()
    }

    private fun stats(bitmap: Bitmap): LumaStats {
        val builder = LumaStats.Builder()
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height step 2) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width step 2) builder.add(row[x])
        }
        return builder.build()
    }

    /** Channel differences between [a] and [b]: how many values differ by 0, 1, 2, 3 and 4 or more 8-bit levels. */
    private fun difference(a: Bitmap, b: Bitmap): LongArray {
        val ra = IntArray(a.width)
        val rb = IntArray(a.width)
        val counts = LongArray(5)
        for (y in 0 until a.height) {
            a.getPixels(ra, 0, a.width, 0, y, a.width, 1)
            b.getPixels(rb, 0, a.width, 0, y, a.width, 1)
            for (x in 0 until a.width) {
                for (shift in intArrayOf(0, 8, 16)) {
                    val d = Math.abs(((ra[x] shr shift) and 0xFF) - ((rb[x] shr shift) and 0xFF))
                    counts[minOf(d, 4)]++
                }
            }
        }
        return counts
    }

    @Test
    fun baseRenderingDeepensBlacksAndAddsContrast() = runBlocking {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        assumeNotNull(id)
        val sizes = StillCaptureStream.sizes(manager.getCameraCharacteristics(id!!))
        assumeNotNull(sizes)
        texture.setDefaultBufferSize(sizes!!.preview.width, sizes.preview.height)
        val stream = StillCaptureStream.open(manager, id, surface, sizes.jpeg, handler) { }
        val jpeg = try {
            delay(1_500) // Let AE and AWB settle.
            stream.capture(0, StillCaptureStream.DEFAULT_QUALITY) { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
        } finally {
            stream.close()
        }
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options))
        val before = stats(bitmap)
        val reference = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val ms = PhotoFinisher.applyBase(bitmap)
        val after = stats(bitmap)
        Log.i("UcPhoto", "RENDER_STATS ${bitmap.width}x${bitmap.height} in $ms ms: camera $before -> rendered $after")
        // D90: the GPU pass must match the CPU reference within one 8-bit level on a real photo.
        val cpuMs = PhotoFinisher.applyBase(reference, gpu = false)
        val counts = difference(bitmap, reference)
        val beyondOne = counts[2] + counts[3] + counts[4]
        Log.i("UcPhoto", "RENDER_GPU_VS_CPU levels 0/1/2/3/4+: ${counts.joinToString("/")} (cpu $cpuMs ms, gpu ok ${PhotoGpu.health.gpuUsable})")
        reference.recycle()
        bitmap.recycle()
        // Float sums in another order move the plan by a few ulps; a pixel sitting exactly on the skin or sky test's
        // edge can then take the other branch (the CPU itself jumps there), so a handful may differ by more.
        assertTrue("GPU vs CPU: $beyondOne values beyond one level", beyondOne <= counts.sum() / 100_000)
        assertTrue("blacks: $before -> $after", after.p1 <= before.p1)
        assertTrue("spread: $before -> $after", after.sd >= before.sd)
        assertTrue("clipping: $before -> $after", after.clipped <= before.clipped + 0.01)
        assertTrue("render took $ms ms", ms < 1_000)
    }
}
