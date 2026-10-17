package com.qtekfun.ultimatecamera.core.gpu.portrait

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The GPU portrait kernels must give the CPU reference's result (D83): the gather blur within 8-bit rounding (the GPU
 * writes 8 bits), the block matching the same disparity almost everywhere (float sums in a different order may flip
 * near-ties). Synthetic scenes only.
 */
@RunWith(AndroidJUnit4::class)
class PortraitKernelsGpuTest {
    private val w = 320
    private val h = 240

    /** Sharp subject (CoC 0) in a disc, a textured background whose CoC grows to the right, a band without depth. */
    private fun scene(): Pair<RgbTile, Plane> {
        val random = Random(11)
        val image = RgbTile(w, h)
        val coc = Plane(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val subject = (x - 100) * (x - 100) + (y - 120) * (y - 120) < 60 * 60
                image.r.data[i] = if (subject) 0.9f else 0.5f + 0.4f * sin(x * 0.7f) * sin(y * 0.5f)
                image.g.data[i] = random.nextInt(256) / 255f
                image.b.data[i] = (x % 16) / 15f
                coc.data[i] = when {
                    subject -> 0f
                    y < 10 -> 0f
                    else -> x / 320f * 2f * DepthBlur.MAX_RADIUS
                }
            }
        }
        return image to coc
    }

    private fun difference(a: RgbTile, b: RgbTile): Pair<Double, Float> {
        var sum = 0.0
        var worst = 0f
        for (c in 0 until 3) {
            val pa = a.channels[c].data
            val pb = b.channels[c].data
            for (i in pa.indices) {
                val d = abs(pa[i] - pb[i])
                sum += d
                worst = maxOf(worst, d)
            }
        }
        return sum / (3.0 * w * h) * 255.0 to worst * 255f
    }

    @Test
    fun gatherMatchesTheCpuReference() {
        val (image, coc) = scene()
        val cpu = DepthBlur.gather(image, coc)
        val gpu = GpuPortraitKernels().use { it.gather(image, coc, DiscKernel.CAPTURE, DepthBlur.MAX_RADIUS) }
        val (mean, worst) = difference(cpu, gpu)
        assertTrue("mean $mean levels, worst $worst", mean < 0.75 && worst <= 4f)
        // In-focus pixels are the camera's pixels on both.
        assertEquals(image.r[100, 120], gpu.r[100, 120], 1f / 255f)
    }

    @Test
    fun liveKernelAndRadiusMatchToo() {
        val (image, coc) = scene()
        val cpu = DepthBlur.gather(image, coc, DiscKernel.LIVE, 8f)
        val gpu = GpuPortraitKernels().use { it.gather(image, coc, DiscKernel.LIVE, 8f) }
        val (mean, worst) = difference(cpu, gpu)
        assertTrue("mean $mean levels, worst $worst", mean < 0.75 && worst <= 4f)
    }

    @Test
    fun blockMatchingMatchesTheCpuReference() {
        val sw = 160
        val sh = 120
        val random = Random(3)
        val texture = FloatArray((sw + 20) * sh) { random.nextFloat() }
        val main = Plane(sw, sh)
        val second = Plane(sw, sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                main[x, y] = if (y < 20) 0.5f else texture[y * (sw + 20) + x + 10]
                val shift = if (x < sw / 2) 5 else 1
                second[x, y] = if (y < 20) 0.5f else texture[y * (sw + 20) + x + 10 - shift]
            }
        }
        val cpu = StereoDepth.disparity(main, second, 8)
        val gpu = GpuPortraitKernels().use { it.disparity(main, second, 8, PortraitKernels.DEFAULT_BLOCK_RADIUS) }
        var same = 0
        for (i in cpu.data.indices) if (cpu.data[i] == gpu.data[i]) same++
        assertTrue("same disparity on $same of ${cpu.data.size}", same >= cpu.data.size * 98 / 100)
        // The flat band has no depth on both.
        assertEquals(Bokeh.INVALID, gpu[80, 5], 0f)
        assertEquals(5f, gpu[30, 60], 0f)
    }

    @Test
    fun fallbackKernelsUseTheGpuOnAPhone() {
        val health = KernelHealth()
        FallbackPortraitKernels(health, ::GpuPortraitKernels).use { kernels ->
            val (image, coc) = scene()
            kernels.gather(image, coc, DiscKernel.CAPTURE, DepthBlur.MAX_RADIUS)
            assertEquals("gpu", kernels.name)
        }
        assertTrue(health.failure ?: "", health.gpuUsable)
    }

    @Test
    fun logsTheCaptureBlurTimings() {
        // The capture blur runs on a 1024 x 768 copy; both timings go to the log for the D83 table (no hard bound:
        // phones differ too much), the run only checks that the GPU pass completes at that size.
        val image = RgbTile(1024, 768)
        val coc = Plane(1024, 768).apply { data.fill(2f * DepthBlur.MAX_RADIUS) }
        val t0 = System.nanoTime()
        DepthBlur.gather(image, coc)
        val t1 = System.nanoTime()
        GpuPortraitKernels().use { it.gather(image, coc, DiscKernel.CAPTURE, DepthBlur.MAX_RADIUS) }
        val t2 = System.nanoTime()
        Log.i("UcPortrait", "gather 1024x768: cpu ${(t1 - t0) / 1_000_000} ms, gpu ${(t2 - t1) / 1_000_000} ms")
    }
}
