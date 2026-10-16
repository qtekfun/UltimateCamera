package com.qtekfun.ultimatecamera.core.gpu

import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.gl.EglCore
import com.qtekfun.ultimatecamera.core.gpu.gl.FrameStampRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [FrameStampRenderer] on the device's GPU must draw what [FrameStamp.renderRow] specifies, where the monitor will read
 * it: the row `round(0.02 × height)` counted from the top of the image, which is GL row `height - 1 - that` because
 * `glReadPixels` counts from the bottom. Luma is BT.601 full range (`Y = 0.299 R + 0.587 G + 0.114 B`); for pure black
 * and white every matrix gives the same values, and the decoder's threshold is adaptive anyway.
 */
@RunWith(AndroidJUnit4::class)
class FrameStampRendererTest {
    private lateinit var egl: EglCore
    private lateinit var renderer: FrameStampRenderer
    private val counters = intArrayOf(0, 1, 2, 0x00FF, 0x1234, 0x8000, 0xBEEF, 0xFFFF, 0x10000, 0x10001)

    @Before
    fun setUp() {
        egl = EglCore(EglColorDepth.RGBA8888, recordable = false)
        egl.makeCurrent(egl.createPbufferSurface(16, 16))
        renderer = FrameStampRenderer()
    }

    @After
    fun tearDown() {
        renderer.release()
        egl.release()
    }

    @Test
    fun decodesAt720p() = checkSize(1280, 720)

    @Test
    fun decodesAt360pAnd1080p() {
        checkSize(640, 360)
        checkSize(1920, 1080)
    }

    /** Only the top strip is drawn: the row just below it keeps the frame content (here the clear colour). */
    @Test
    fun drawsOnlyTheTopStrip() {
        val w = 1280
        val h = 720
        val fbo = framebuffer(w, h)
        GLES30.glClearColor(0.5f, 0.25f, 0.75f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        renderer.draw(0xFFFF, w, h)
        val strip = FrameStamp.stripHeight(h)
        val below = readRow(w, h - 1 - strip)
        for (x in 0 until w) {
            val r = below.get(x * 4).toInt() and 0xFF
            val g = below.get(x * 4 + 1).toInt() and 0xFF
            assertTrue("pixel $x below the strip was overwritten: r=$r g=$g", abs(r - 128) <= 1 && abs(g - 64) <= 1)
        }
        val top = luma(readRow(w, h - 1), w)
        assertArrayEquals(FrameStamp.renderRow(0xFFFF, w), top)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
    }

    private fun checkSize(w: Int, h: Int) {
        val fbo = framebuffer(w, h)
        GLES30.glClearColor(0.5f, 0.5f, 0.5f, 1f)
        val glRow = h - 1 - FrameStamp.sampleRow(h)
        for (counter in counters) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            renderer.draw(counter, w, h)
            val row = luma(readRow(w, glRow), w)
            assertArrayEquals("GPU row differs from the CPU reference, counter $counter, ${w}x$h", FrameStamp.renderRow(counter, w), row)
            assertEquals("${w}x$h", counter and FrameStamp.COUNTER_MASK, FrameStamp.decode(row, w))
        }
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
    }

    private fun readRow(w: Int, glRow: Int): ByteBuffer {
        val out = ByteBuffer.allocateDirect(w * 4).order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GLES30.glReadPixels(0, glRow, w, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
        return out
    }

    /** BT.601 full-range luma of an RGBA8 row. */
    private fun luma(rgba: ByteBuffer, w: Int): IntArray = IntArray(w) { x ->
        val r = rgba.get(x * 4).toInt() and 0xFF
        val g = rgba.get(x * 4 + 1).toInt() and 0xFF
        val b = rgba.get(x * 4 + 2).toInt() and 0xFF
        (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
    }

    private fun framebuffer(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ids[0], 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        assertTrue("framebuffer incomplete: 0x${Integer.toHexString(status)}", status == GLES30.GL_FRAMEBUFFER_COMPLETE)
        GLES30.glViewport(0, 0, width, height)
        return fbo[0]
    }
}
