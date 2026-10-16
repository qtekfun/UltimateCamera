package com.qtekfun.ultimatecamera.core.gpu

import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.gl.EglCore
import com.qtekfun.ultimatecamera.core.gpu.gl.LutRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The GLSL LUT pass must compute what [LutMath] computes (and [LutMath] is pinned to PNG references by the JVM tests).
 * Runs on the device's GPU; the measured precision is logged under the UCSPIKE tag for the spike report.
 */
@RunWith(AndroidJUnit4::class)
class LutShaderReferenceTest {
    private lateinit var egl: EglCore
    private val w = ReferenceImages.WIDTH
    private val h = ReferenceImages.HEIGHT

    @Before
    fun setUp() {
        egl = EglCore(EglColorDepth.RGBA8888, recordable = false)
        egl.makeCurrent(egl.createPbufferSurface(w, h))
    }

    @After
    fun tearDown() = egl.release()

    @Test
    fun hardwareInterpolationMatchesReferenceIn8Bit() = check8(LutInterpolation.HARDWARE, tolerance = 2)

    @Test
    fun manualInterpolationMatchesReferenceIn8Bit() = check8(LutInterpolation.MANUAL_TRILINEAR, tolerance = 1)

    /** Float render target: shows the real precision of each mode, which decides the 10-bit path (REQ-COL-005). */
    @Test
    fun floatPrecisionOfBothModes() {
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        assumeTrue("no float colour buffers", "GL_EXT_color_buffer_float" in extensions)
        val lut = SpikeLuts.creative(33)
        // A fine ramp in every channel combination: 1024 levels along x, three bands of channel mixes along y.
        val width = 1024
        val height = 3
        val input = FloatArray(width * height * 4) { i ->
            val px = i / 4
            val x = (px % width) / (width - 1f)
            val band = px / width
            when (i % 4) {
                0 -> x
                1 -> if (band == 0) x else 1f - x
                2 -> if (band == 2) x * 0.5f else 0.25f
                else -> 1f
            }
        }
        val expected = FloatArray(3)
        val worst = LutInterpolation.entries.associateWith { mode ->
            val out = renderFloat(lut, mode, input, width, height)
            var err = 0f
            for (p in 0 until width * height) {
                LutMath.apply(lut, input[p * 4], input[p * 4 + 1], input[p * 4 + 2], expected)
                for (c in 0..2) err = max(err, abs(out[p * 4 + c] - expected[c]))
            }
            err
        }
        worst.forEach { (mode, err) -> Log.i("UCSPIKE", "LUT_PRECISION $mode maxError=$err (${err * 1023} 10-bit codes)") }
        // Manual trilinear in highp must stay within about one 10-bit code (half-float LUT storage included).
        assertTrue("manual error ${worst[LutInterpolation.MANUAL_TRILINEAR]}", worst.getValue(LutInterpolation.MANUAL_TRILINEAR) < 2f / 1023f)
    }

    private fun check8(mode: LutInterpolation, tolerance: Int) {
        val lut = SpikeLuts.creative(33)
        val input = ReferenceImages.gradient()
        val expected = LutMath.applyToArgb(lut, input)
        val bytes = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        input.forEach { p ->
            bytes.put(((p shr 16) and 0xFF).toByte()).put(((p shr 8) and 0xFF).toByte()).put((p and 0xFF).toByte()).put(0xFF.toByte())
        }
        bytes.position(0)
        val source = texture2d(GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, w, h, bytes)
        val target = framebuffer(GLES30.GL_RGBA8, w, h)
        val renderer = LutRenderer(InputKind.TEXTURE_2D, mode).apply { setLut(lut) }
        renderer.draw(source, w, h)
        val out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
        renderer.release()
        GLES30.glDeleteFramebuffers(1, intArrayOf(target), 0)

        var worst = 0
        for (i in 0 until w * h) {
            val e = expected[i]
            val got = intArrayOf(out.get(i * 4).toInt() and 0xFF, out.get(i * 4 + 1).toInt() and 0xFF, out.get(i * 4 + 2).toInt() and 0xFF)
            val want = intArrayOf((e shr 16) and 0xFF, (e shr 8) and 0xFF, e and 0xFF)
            for (c in 0..2) worst = max(worst, abs(got[c] - want[c]))
        }
        Log.i("UCSPIKE", "LUT_8BIT $mode maxDiff=$worst/255")
        assertTrue("$mode differs from the CPU reference by $worst/255", worst <= tolerance)
    }

    private fun renderFloat(lut: CubeLut, mode: LutInterpolation, input: FloatArray, width: Int, height: Int): FloatArray {
        val buffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(input).position(0)
        val source = texture2d(GLES30.GL_RGBA32F, GLES30.GL_RGBA, GLES30.GL_FLOAT, width, height, buffer)
        val target = framebuffer(GLES30.GL_RGBA32F, width, height)
        val renderer = LutRenderer(InputKind.TEXTURE_2D, mode).apply { setLut(lut) }
        renderer.draw(source, width, height)
        val out = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_FLOAT, out)
        renderer.release()
        GLES30.glDeleteFramebuffers(1, intArrayOf(target), 0)
        return FloatArray(input.size).also { out.asFloatBuffer().get(it) }
    }

    private fun texture2d(internal: Int, format: Int, type: Int, width: Int, height: Int, data: java.nio.Buffer): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, data)
        return ids[0]
    }

    private fun framebuffer(internal: Int, width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internal, width, height)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ids[0], 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        assertTrue("framebuffer incomplete: 0x${Integer.toHexString(status)}", status == GLES30.GL_FRAMEBUFFER_COMPLETE)
        return fbo[0]
    }
}
