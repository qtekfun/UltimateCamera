package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitKernelsTest {
    private fun scene(w: Int, h: Int): Pair<RgbTile, Plane> {
        val random = Random(7)
        val image = RgbTile(w, h)
        val coc = Plane(w, h)
        for (i in 0 until w * h) {
            image.r.data[i] = random.nextFloat()
            image.g.data[i] = random.nextFloat()
            image.b.data[i] = random.nextFloat()
            coc.data[i] = if (i % w < w / 3) 0f else 12f
        }
        return image to coc
    }

    @Test
    fun captureKernelIsTheGoldenAngleSpiralTheBlurAlwaysUsed() {
        // The same 96 points the CPU blur used before the GPU port: the shader gets these exact floats.
        val k = DiscKernel.CAPTURE
        assertEquals(96, k.samples)
        val golden = PI * (3.0 - sqrt(5.0))
        for (i in listOf(0, 1, 47, 95)) {
            val r = sqrt((i + 0.5) / 96)
            assertEquals((r * cos(i * golden)).toFloat(), k.x[i], 0f)
            assertEquals((r * sin(i * golden)).toFloat(), k.y[i], 0f)
            assertEquals(sqrt((i + 0.5f) / 96), k.distance[i], 0f)
        }
        assertEquals(32, DiscKernel.LIVE.samples)
    }

    @Test
    fun packedKernelInterleavesXYAndDistanceForTheUniform() {
        val k = DiscKernel(3)
        assertArrayEquals(floatArrayOf(k.x[0], k.y[0], k.distance[0], k.x[1], k.y[1], k.distance[1], k.x[2], k.y[2], k.distance[2]), k.packed(), 0f)
        assertThrows(IllegalArgumentException::class.java) { DiscKernel(0) }
        assertThrows(IllegalArgumentException::class.java) { DiscKernel(DiscKernel.MAX_SAMPLES + 1) }
    }

    @Test
    fun fewerSamplesStillKeepTheSubjectSharpAndBlurTheRest() {
        val (image, coc) = scene(48, 24)
        val live = DepthBlur.gather(image, coc, DiscKernel.LIVE)
        for (y in 0 until 24) {
            assertEquals(image.r[2, y], live.r[2, y], 0f)
            assertEquals(0.5f, live.r[40, y], 0.2f)
        }
    }

    @Test
    fun maxRadiusBoundsTheBlur() {
        val (image, coc) = scene(48, 24)
        val capped = DepthBlur.gather(image, coc, DiscKernel.CAPTURE, maxRadius = 0.25f)
        // A radius under half a pixel is no blur at all.
        assertArrayEquals(image.r.data, capped.r.data, 0f)
    }

    @Test
    fun gatherRejectsMismatchedSizes() {
        assertThrows(IllegalArgumentException::class.java) { DepthBlur.gather(RgbTile(4, 4), Plane(4, 3)) }
    }

    @Test
    fun cpuKernelsAreTheReferenceFunctions() {
        val (image, coc) = scene(40, 20)
        assertArrayEquals(DepthBlur.gather(image, coc).g.data, CpuPortraitKernels.gather(image, coc, DiscKernel.CAPTURE, DepthBlur.MAX_RADIUS).g.data, 0f)
        val main = image.r
        val second = image.g
        assertArrayEquals(StereoDepth.disparity(main, second, 3).data, CpuPortraitKernels.disparity(main, second, 3, 3).data, 0f)
        assertEquals("cpu", CpuPortraitKernels.name)
    }

    /** A stand-in for the GPU kernels that can be told to fail. */
    private class FakeGpu(private val failGather: Boolean = false) : PortraitKernels {
        override val name = "gpu"
        var closed = false
        var calls = 0

        override fun gather(image: RgbTile, coc: Plane, kernel: DiscKernel, maxRadius: Float): RgbTile {
            calls++
            check(!failGather) { "shader compile failed" }
            return RgbTile(image.width, image.height)
        }

        override fun disparity(main: Plane, second: Plane, maxDisparity: Int, blockRadius: Int): Plane {
            calls++
            return Plane(main.width, main.height)
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun fallbackUsesTheGpuWhileItWorks() {
        val gpu = FakeGpu()
        val health = KernelHealth()
        val kernels = FallbackPortraitKernels(health, { gpu })
        val (image, coc) = scene(8, 4)
        kernels.gather(image, coc, DiscKernel.CAPTURE, DepthBlur.MAX_RADIUS)
        kernels.disparity(image.r, image.g, 2, 1)
        assertEquals(2, gpu.calls)
        assertEquals("gpu", kernels.name)
        assertTrue(health.gpuUsable)
        kernels.close()
        assertTrue(gpu.closed)
    }

    @Test
    fun aGpuThatFailsGivesTheCpuResultAndIsNotTriedAgain() {
        val gpu = FakeGpu(failGather = true)
        val health = KernelHealth()
        val reasons = ArrayList<String>()
        val kernels = FallbackPortraitKernels(health, { gpu }) { reasons += it }
        val (image, coc) = scene(24, 12)
        val out = kernels.gather(image, coc, DiscKernel.CAPTURE, DepthBlur.MAX_RADIUS)
        assertArrayEquals(DepthBlur.gather(image, coc).r.data, out.r.data, 0f)
        assertTrue(gpu.closed)
        assertEquals("cpu", kernels.name)
        assertFalse(health.gpuUsable)
        assertTrue(health.failure!!.contains("shader compile failed"))
        assertEquals(1, reasons.size)
        // The next shot's kernels never open the GPU.
        var opened = false
        val next = FallbackPortraitKernels(health, { FakeGpu().also { opened = true } })
        next.disparity(image.r, image.g, 2, 1)
        assertFalse(opened)
        assertEquals("cpu", next.name)
    }

    @Test
    fun aGpuThatCannotEvenOpenFallsBackToo() {
        val health = KernelHealth()
        val kernels = FallbackPortraitKernels(health, { error("no EGL") })
        val main = Plane(16, 8) { i -> (i % 5) / 5f }
        assertArrayEquals(StereoDepth.disparity(main, main, 2, 1).data, kernels.disparity(main, main, 2, 1).data, 0f)
        health.fail("second reason")
        assertTrue("the first failure is kept", health.failure!!.contains("no EGL"))
    }

    @Test
    fun uploadsAreInterleavedNativeOrderFloats() {
        val tile = RgbTile(2, 1).apply {
            r.data[0] = 0.1f
            g.data[0] = 0.2f
            b.data[0] = 0.3f
            r.data[1] = 0.4f
        }
        val rgba = PortraitGpuData.rgba(tile).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val values = FloatArray(8).also { rgba.get(it) }
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 1f, 0.4f, 0f, 0f, 1f), values, 0f)
        val floats = PortraitGpuData.floats(floatArrayOf(-1f, 2.5f)).order(ByteOrder.nativeOrder()).asFloatBuffer()
        assertEquals(-1f, floats.get(0), 0f)
        assertEquals(2.5f, floats.get(1), 0f)
    }

    @Test
    fun readBacksDecodeColourAndDisparity() {
        val bytes = ByteBuffer.wrap(byteArrayOf(255.toByte(), 0, 51, 255.toByte(), 3, 0, 0, 255.toByte()))
        val tile = PortraitGpuData.tile(bytes, 2, 1)
        assertEquals(1f, tile.r[0, 0], 0f)
        assertEquals(0.2f, tile.b[0, 0], 1e-6f)
        val disparity = PortraitGpuData.disparity(bytes, 2, 1)
        assertEquals("255 is no depth", Bokeh.INVALID, disparity[0, 0], 0f)
        assertEquals(3f, disparity[1, 0], 0f)
    }

    @Test
    fun offsetsComeInTheOrderBothMatchersUse() {
        assertEquals(9, StereoDepth.offsetCount(2))
        val offsets = (0 until StereoDepth.offsetCount(2)).map(StereoDepth::offset)
        assertEquals(listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 2 to 0, -2 to 0, 0 to 2, 0 to -2), offsets)
    }

    @Test
    fun shadersAgreeWithTheCpuOnTheirConstants() {
        val plane = PortraitShaders.gather(PortraitShaders.CocSource.PLANE)
        val live = PortraitShaders.gather(PortraitShaders.CocSource.LIVE_DEPTH)
        assertTrue(plane.contains("uKernel[${DiscKernel.MAX_SAMPLES}]"))
        assertTrue(plane.contains("uniform highp sampler2D uCoc;"))
        assertFalse(plane.contains("uDepth"))
        // No depth (Bokeh.INVALID) is no blur, as in Bokeh.cocPx; the live copy's rows are flipped against the depth map.
        assertTrue(live.contains("inverse <= ${Bokeh.INVALID}"))
        assertTrue(live.contains("1.0 - uv.y"))
        assertTrue(PortraitShaders.STEREO.contains("int count = 1 + 4 * uMaxDisparity;"))
        assertEquals(255, PortraitShaders.STEREO_INVALID)
    }

    @Test
    fun theGpuRefusesWhatItCannotDrawSoTheCpuTakesOver() {
        assertEquals(null, PortraitGpuData.problem(1024, 768, maxTextureSize = 4096))
        assertTrue(PortraitGpuData.problem(8192, 768, maxTextureSize = 4096)!!.contains("GL_MAX_TEXTURE_SIZE"))
        assertTrue(PortraitGpuData.problem(0, 768, maxTextureSize = 4096)!!.contains("empty"))
        assertEquals(null, PortraitGpuData.problem(160, 120, 4096, maxDisparity = 254))
        assertTrue("255 is the no-depth marker", PortraitGpuData.problem(160, 120, 4096, maxDisparity = 255)!!.contains("8 bits"))
    }

    @Test
    fun liveShaderUniformsFollowTheAperture() {
        val depth = Plane(4, 4)
        val open = LiveDepth(depth, focus = 1.5f, fNumber = 2f)
        assertEquals(1.5f, open.shaderFocus, 0f)
        assertEquals(Bokeh.cocScale(2f, 480), open.shaderScale(480), 0f)
        val off = LiveDepth(depth, focus = 1.5f, fNumber = 0f)
        assertEquals("slider off: no blur", Bokeh.INVALID, off.shaderFocus, 0f)
        assertEquals(0f, off.shaderScale(480), 0f)
    }

    private fun Plane(w: Int, h: Int, value: (Int) -> Float): Plane = Plane(w, h, FloatArray(w * h, value))
}
