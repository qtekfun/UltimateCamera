package com.qtekfun.ultimatecamera.core.gpu.hdr

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.gl.EglCore
import com.qtekfun.ultimatecamera.core.gpu.gl.GlException
import com.qtekfun.ultimatecamera.core.gpu.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * [FastExposureFusion] with its two full-resolution passes on the GPU (D64 follow-up: the CPU passes took 15 s for
 * 12 MP on the Pixel 8). The three I420 frames are uploaded once as R8 textures; the GPU box-averages them to the
 * small images, the CPU fuses those (Mertens, all cores), and the GPU writes the full-size result upright in strips
 * of [STRIP] rows (detail blend and turn in one fragment pass) plus the 1/4-size gain map. Memory on the CPU side: one
 * strip buffer; on the GPU: the frames (1.5 bytes per pixel each) and one strip target.
 *
 * Owns its own EGL context: create, use and [release] on one thread.
 */
class GpuExposureFusion(private val width: Int, private val height: Int, val factor: Int = FastExposureFusion.DEFAULT_FACTOR) {
    val lowW = (width + factor - 1) / factor
    val lowH = (height + factor - 1) / factor
    private val egl = EglCore(EglColorDepth.RGBA8888, recordable = false)
    private val surface = egl.createPbufferSurface(1, 1)
    private val downscale: Int
    private val output: Int
    private val gain: Int
    private val frames = IntArray(FRAMES * 3)
    private val lows = IntArray(FRAMES + 1)
    private val target = IntArray(1)
    private val fbo = IntArray(1)

    init {
        egl.makeCurrent(surface)
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
        if (max[0] < maxOf(width, height)) throw GlException("GL_MAX_TEXTURE_SIZE ${max[0]} < ${maxOf(width, height)}")
        downscale = GlUtil.compileProgram(FusionShaders.VERTEX, FusionShaders.DOWNSCALE)
        output = GlUtil.compileProgram(FusionShaders.VERTEX, FusionShaders.OUTPUT)
        gain = GlUtil.compileProgram(FusionShaders.VERTEX, FusionShaders.GAIN)
        GLES30.glGenTextures(frames.size, frames, 0)
        GLES30.glGenTextures(lows.size, lows, 0)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GlUtil.checkError("fusion setup")
    }

    /** Uploads frame [k] (I420, [width] × [height], from [i420]'s position) as three R8 textures. */
    fun upload(k: Int, i420: ByteBuffer) {
        val base = i420.position()
        val cw = width / 2
        val ch = height / 2
        texture(frames[k * 3], width, height, i420.duplicate().position(base) as ByteBuffer)
        texture(frames[k * 3 + 1], cw, ch, i420.duplicate().position(base + width * height) as ByteBuffer)
        texture(frames[k * 3 + 2], cw, ch, i420.duplicate().position(base + width * height + cw * ch) as ByteBuffer)
        GlUtil.checkError("upload frame $k")
    }

    private fun texture(id: Int, w: Int, h: Int, data: ByteBuffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, data)
        filter(GLES30.GL_NEAREST)
    }

    private fun filter(mode: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, mode)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, mode)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun bindFrame(program: Int, k: Int, slot: Int) {
        for ((i, plane) in listOf("uY", "uU", "uV").withIndex()) {
            val unit = slot * 3 + i
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frames[k * 3 + i])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "$plane$slot"), unit)
        }
    }

    /** Frame [k] box-averaged over [factor] × [factor] blocks ([lowW] × [lowH], 8-bit precision). */
    fun downscale(k: Int): RgbTile {
        target(lowW, lowH)
        GLES30.glUseProgram(downscale)
        bindFrame(downscale, k, 0)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(downscale, "uSize"), width, height)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(downscale, "uFactor"), factor)
        GLES30.glViewport(0, 0, lowW, lowH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val bytes = ByteBuffer.allocateDirect(lowW * lowH * 4)
        GLES30.glReadPixels(0, 0, lowW, lowH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkError("downscale $k")
        val out = RgbTile(lowW, lowH)
        for (i in 0 until lowW * lowH) {
            out.r.data[i] = (bytes.get(i * 4).toInt() and 0xFF) / 255f
            out.g.data[i] = (bytes.get(i * 4 + 1).toInt() and 0xFF) / 255f
            out.b.data[i] = (bytes.get(i * 4 + 2).toInt() and 0xFF) / 255f
        }
        return out
    }

    /** A colour target of [w] × [h] bound to the framebuffer (re-allocated only when the size changes). */
    private var targetSize = 0 to 0

    private fun target(w: Int, h: Int) {
        if (target[0] == 0) GLES30.glGenTextures(1, target, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        if (targetSize != w to h) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            filter(GLES30.GL_NEAREST)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, target[0], 0)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) throw GlException("fusion framebuffer 0x${Integer.toHexString(status)}")
            targetSize = w to h
        }
    }

    /**
     * Uploads the small images the CPU made: [fused] and, per frame, its aligned low image with the normalised weight
     * in alpha.
     */
    fun setLow(fused: RgbTile, low: List<RgbTile>, weights: List<Plane>) {
        lowTexture(lows[0], fused, null)
        low.forEachIndexed { k, t -> lowTexture(lows[k + 1], t, weights[k]) }
        GlUtil.checkError("low images")
    }

    private fun lowTexture(id: Int, t: RgbTile, alpha: Plane?) {
        val data = FloatBuffer.allocate(t.width * t.height * 4)
        for (i in 0 until t.width * t.height) {
            data.put(t.r.data[i]).put(t.g.data[i]).put(t.b.data[i]).put(alpha?.data?.get(i) ?: 1f)
        }
        data.position(0)
        val direct = ByteBuffer.allocateDirect(data.capacity() * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        direct.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, t.width, t.height, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, direct)
        filter(GLES30.GL_LINEAR)
    }

    private fun bindBlend(program: Int, shifts: List<Pair<Int, Int>>, turn: Int, outW: Int, outH: Int) {
        GLES30.glUseProgram(program)
        for (k in 0 until FRAMES) bindFrame(program, k, k)
        val names = listOf("uFused", "uLow0", "uLow1", "uLow2")
        names.forEachIndexed { i, name ->
            val unit = FRAMES * 3 + i
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lows[i])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
        }
        shifts.forEachIndexed { k, (dx, dy) -> GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uShift$k"), dx, dy) }
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uSize"), width, height)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uOut"), outW, outH)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTurn"), turn)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uLowScale"), 1f / (factor * lowW), 1f / (factor * lowH))
    }

    /**
     * Renders the fused photo turned clockwise by [rotation] and hands it to [strip] as ARGB rows ([y0], row count,
     * pixels) from the top. The IntArray is reused between calls.
     */
    fun render(shifts: List<Pair<Int, Int>>, rotation: Int, strip: (y0: Int, rows: Int, argb: IntArray) -> Unit) {
        val turn = ((rotation % FULL_TURN) + FULL_TURN) % FULL_TURN
        val outW = if (turn == QUARTER || turn == 3 * QUARTER) height else width
        val outH = if (turn == QUARTER || turn == 3 * QUARTER) width else height
        target(outW, STRIP)
        bindBlend(output, shifts, turn, outW, outH)
        val location = GLES30.glGetUniformLocation(output, "uStripY")
        val bytes = ByteBuffer.allocateDirect(outW * STRIP * 4).order(ByteOrder.LITTLE_ENDIAN)
        val ints = IntArray(outW * STRIP)
        var y0 = 0
        while (y0 < outH) {
            val rows = minOf(STRIP, outH - y0)
            GLES30.glUniform1i(location, y0)
            GLES30.glViewport(0, 0, outW, rows)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            bytes.position(0)
            GLES30.glReadPixels(0, 0, outW, rows, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            bytes.position(0)
            bytes.asIntBuffer().get(ints, 0, outW * rows)
            strip(y0, rows, ints)
            y0 += rows
        }
        GlUtil.checkError("render")
    }

    /** The 1/4-size gain map of the upright photo, as ALPHA_8 pixels (value in the top byte), row-major. */
    fun gainMap(shifts: List<Pair<Int, Int>>, rotation: Int, exposures: DoubleArray, gmW: Int, gmH: Int): IntArray {
        val turn = ((rotation % FULL_TURN) + FULL_TURN) % FULL_TURN
        val outW = if (turn == QUARTER || turn == 3 * QUARTER) height else width
        val outH = if (turn == QUARTER || turn == 3 * QUARTER) width else height
        target(gmW, gmH)
        bindBlend(gain, shifts, turn, outW, outH)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gain, "uStripY"), 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(gain, "uExposure"), exposures[0].toFloat(), exposures[1].toFloat(), exposures[2].toFloat())
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gain, "uDarkest"), exposures.indices.minByOrNull { exposures[it] } ?: 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gain, "uMaxRatio"), GainMapMath.MAX_RATIO)
        GLES30.glViewport(0, 0, gmW, gmH)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val bytes = ByteBuffer.allocateDirect(gmW * gmH * 4).order(ByteOrder.LITTLE_ENDIAN)
        GLES30.glReadPixels(0, 0, gmW, gmH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        GlUtil.checkError("gain map")
        return IntArray(gmW * gmH).also {
            bytes.position(0)
            bytes.asIntBuffer().get(it)
        }
    }

    fun release() {
        runCatching {
            GLES30.glDeleteProgram(downscale)
            GLES30.glDeleteProgram(output)
            GLES30.glDeleteProgram(gain)
            GLES30.glDeleteTextures(frames.size, frames, 0)
            GLES30.glDeleteTextures(lows.size, lows, 0)
            if (target[0] != 0) GLES30.glDeleteTextures(1, target, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
        }
        egl.releaseSurface(surface)
        egl.release()
    }

    companion object {
        const val FRAMES = 3

        /** Rows per output strip: 4000 × 256 × 4 bytes = 4 MB of read-back buffer. */
        const val STRIP = 256
        private const val FULL_TURN = 360
        private const val QUARTER = 90
    }
}
