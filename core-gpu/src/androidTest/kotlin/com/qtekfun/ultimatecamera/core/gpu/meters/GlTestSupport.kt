package com.qtekfun.ultimatecamera.core.gpu.meters

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertTrue

/** Texture, framebuffer and readback helpers for the meter shader tests (GL order everywhere). */
internal object GlTestSupport {
    /** Uploads an ARGB image as an RGBA8 texture with linear filtering and clamp-to-edge, like a preview texture. */
    fun upload(pixels: IntArray, width: Int, height: Int): Int {
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (p in pixels) bytes.put(((p shr 16) and 0xFF).toByte()).put(((p shr 8) and 0xFF).toByte()).put((p and 0xFF).toByte()).put(0xFF.toByte())
        bytes.position(0)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        return ids[0]
    }

    /** An RGBA8 framebuffer, bound, cleared to opaque black, viewport set to it. Returns (framebuffer, texture). */
    fun framebuffer(width: Int, height: Int): IntArray {
        val ids = IntArray(2)
        GLES30.glGenTextures(1, ids, 1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[1])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
        GLES30.glGenFramebuffers(1, ids, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ids[1], 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        assertTrue("framebuffer incomplete: 0x${Integer.toHexString(status)}", status == GLES30.GL_FRAMEBUFFER_COMPLETE)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        return ids
    }

    fun release(framebuffer: IntArray) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, framebuffer, 0)
        GLES30.glDeleteTextures(1, framebuffer, 1)
    }

    /** Reads the bound framebuffer as opaque ARGB (alpha ignored). */
    fun read(width: Int, height: Int): IntArray {
        val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        return IntArray(width * height) { i ->
            val r = bytes.get(i * 4).toInt() and 0xFF
            val g = bytes.get(i * 4 + 1).toInt() and 0xFF
            val b = bytes.get(i * 4 + 2).toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Result of an image comparison: worst channel difference over the compared pixels and how many were skipped. */
    data class Diff(val worst: Int, val overTolerance: Int, val skipped: Int, val firstBad: Int)

    /**
     * Compares RGB channels. Pixels whose [margins] entry is below [MonitorOverlayMath.AMBIGUOUS] (a threshold
     * decision the GPU may take the other way) are skipped and counted.
     */
    fun compare(expected: IntArray, actual: IntArray, tolerance: Int, margins: FloatArray? = null, ambiguous: Float = MonitorOverlayMath.AMBIGUOUS): Diff {
        var worst = 0
        var over = 0
        var skipped = 0
        var first = -1
        for (i in expected.indices) {
            if (margins != null && margins[i] < ambiguous) {
                skipped++
                continue
            }
            var d = 0
            for (shift in intArrayOf(0, 8, 16)) d = max(d, abs(((expected[i] shr shift) and 0xFF) - ((actual[i] shr shift) and 0xFF)))
            worst = max(worst, d)
            if (d > tolerance) {
                over++
                if (first < 0) first = i
            }
        }
        return Diff(worst, over, skipped, first)
    }

    fun describe(i: Int, width: Int, expected: IntArray, actual: IntArray): String =
        if (i < 0) "" else " first at (${i % width},${i / width}): expected #${Integer.toHexString(expected[i])} got #${Integer.toHexString(actual[i])}"
}
