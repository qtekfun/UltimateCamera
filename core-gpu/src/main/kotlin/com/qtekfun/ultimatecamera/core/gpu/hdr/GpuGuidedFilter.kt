package com.qtekfun.ultimatecamera.core.gpu.hdr

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.gl.GlException
import com.qtekfun.ultimatecamera.core.gpu.gl.GlUtil
import com.qtekfun.ultimatecamera.core.gpu.photo.GpuStripPass
import com.qtekfun.ultimatecamera.core.gpu.photo.PhotoGpuData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [GuidedBase.of] on the GPU (D90; about 0.3 s on the CPU for the first photo after start, which runs it interpreted):
 * four box passes over RGBA32F targets, horizontal then vertical as the CPU does, the vertical one of the first pair
 * turning (mean, mean of squares) into `a` and `b`. Float sums instead of the CPU's double running sums: `a` and `b`
 * differ in the last bits only. Needs float render targets (EXT_color_buffer_float), else it throws and the caller
 * uses the CPU. Runs on the context of the [GpuStripPass] it is made for; make that pass current again after use.
 */
internal class GpuGuidedFilter {
    private val horizontal: Int
    private val vertical: Int
    private val textures = IntArray(3)
    private val fbo = IntArray(1)

    init {
        if ("GL_EXT_color_buffer_float" !in GlUtil.extensions()) throw GlException("no EXT_color_buffer_float")
        horizontal = GlUtil.compileProgram(GpuStripPass.VERTEX, BOX_H)
        vertical = GlUtil.compileProgram(GpuStripPass.VERTEX, BOX_V)
        GLES30.glGenTextures(textures.size, textures, 0)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GlUtil.checkError("guided filter setup")
    }

    fun of(low: Plane, radius: Int, eps: Float): GuidedBase {
        val w = low.width
        val h = low.height
        val n = w * h
        // Centred on the plane's mean: the variance is the same, the float sums of squares stay small and exact enough.
        var total = 0.0
        for (i in 0 until n) total += low.data[i]
        val centre = (total / n).toFloat()
        val input = FloatArray(n * 4)
        for (i in 0 until n) {
            val l = low.data[i] - centre
            input[i * 4] = l
            input[i * 4 + 1] = l * l
        }
        for (id in textures) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            val data = if (id == textures[0]) PhotoGpuData.floats(input) else null
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
            for (p in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, p, GLES30.GL_NEAREST)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(0, 0, w, h)
        box(horizontal, textures[0], textures[1], radius, w, h, 0f, false, centre)
        box(vertical, textures[1], textures[2], radius, w, h, eps, true, centre)
        box(horizontal, textures[2], textures[1], radius, w, h, 0f, false, centre)
        box(vertical, textures[1], textures[2], radius, w, h, 0f, false, centre)
        val out = ByteBuffer.allocateDirect(n * 4 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_FLOAT, out)
        GlUtil.checkError("guided filter")
        val floats = FloatArray(n * 4)
        out.asFloatBuffer().get(floats)
        val a = Plane(w, h)
        val b = Plane(w, h)
        for (i in 0 until n) {
            a.data[i] = floats[i * 4]
            b.data[i] = floats[i * 4 + 1]
        }
        return GuidedBase(a, b)
    }

    @Suppress("LongParameterList")
    private fun box(program: Int, from: Int, to: Int, radius: Int, w: Int, h: Int, eps: Float, toAb: Boolean, centre: Float) {
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, to, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) throw GlException("guided framebuffer 0x${Integer.toHexString(status)}")
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, from)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uIn"), 0)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uSize"), w, h)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uRadius"), radius)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uEps"), eps)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uToAb"), if (toAb) 1 else 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uCentre"), centre)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    @Suppress("TooGenericExceptionCaught") // The context is going away with the pass anyway.
    fun release() {
        try {
            GLES30.glDeleteProgram(horizontal)
            GLES30.glDeleteProgram(vertical)
            GLES30.glDeleteTextures(textures.size, textures, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
        } catch (_: RuntimeException) {
            // Nothing more to free.
        }
    }

    private companion object {
        const val HEAD = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uIn;
uniform ivec2 uSize;
uniform int uRadius;
uniform float uEps;
uniform int uToAb;
uniform float uCentre;
out vec4 color;
"""

        /** Mean of .rg over the window [x − r, x + r] clipped to the plane, as [GuidedBase.boxMean]'s first pass. */
        const val BOX_H = HEAD + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int x0 = max(p.x - uRadius, 0);
    int x1 = min(p.x + uRadius + 1, uSize.x);
    vec2 sum = vec2(0.0);
    for (int x = x0; x < x1; x++) sum += texelFetch(uIn, ivec2(x, p.y), 0).rg;
    color = vec4(sum / float(x1 - x0), 0.0, 1.0);
}
"""

        /** The vertical mean; with uToAb, the centred (mean, mean of squares) become the guided filter's (a, b). */
        const val BOX_V = HEAD + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int y0 = max(p.y - uRadius, 0);
    int y1 = min(p.y + uRadius + 1, uSize.y);
    vec2 sum = vec2(0.0);
    for (int y = y0; y < y1; y++) sum += texelFetch(uIn, ivec2(p.x, y), 0).rg;
    vec2 m = sum / float(y1 - y0);
    if (uToAb == 1) {
        float variance = max(m.y - m.x * m.x, 0.0);
        float a = variance / (variance + uEps);
        float mean = m.x + uCentre;
        m = vec2(a, mean - a * mean);
    }
    color = vec4(m, 0.0, 1.0);
}
"""
    }
}
