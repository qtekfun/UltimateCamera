package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.LogSource
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.LutShaders
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws an input texture through an optional 3D LUT into the current framebuffer. Create and use it on the thread that
 * owns the GL context. All per-frame work is uniform updates and one draw call: no allocations, no GL object creation.
 * With [log] set, the UC-Log curve ([com.qtekfun.ultimatecamera.core.gpu.UcLog]) is applied to the camera signal before
 * the LUT, and also when no LUT is set (flat Log recording).
 */
class LutRenderer(private val input: InputKind, private val interpolation: LutInterpolation, log: LogSource? = null) {
    private val passthrough = GlUtil.compileProgram(LutShaders.VERTEX, LutShaders.fragment(input, lut = null, log = log))
    private val graded = GlUtil.compileProgram(LutShaders.VERTEX, LutShaders.fragment(input, interpolation, log))
    private var lutTexture = 0
    private var lut: CubeLut? = null
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val texMatrixLocationGraded = GLES30.glGetUniformLocation(graded, "uTexMatrix")
    private val texMatrixLocationPassthrough = GLES30.glGetUniformLocation(passthrough, "uTexMatrix")
    private val gainLocationGraded = GLES30.glGetUniformLocation(graded, "uInputGain")
    private val gainLocationPassthrough = GLES30.glGetUniformLocation(passthrough, "uInputGain")
    private val inputGain = floatArrayOf(1f, 1f, 1f)

    init {
        GLES30.glUseProgram(passthrough)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(passthrough, "uInput"), 0)
    }

    /** Per-channel gain on the input signal before everything else (scene white balance); 1, 1, 1 = none. */
    fun setInputGain(gains: FloatArray) {
        gains.copyInto(inputGain, endIndex = 3)
    }

    /** Uploads [newLut] (or clears it with null). Costs one 3D texture upload; call when the LUT changes, not per frame. */
    fun setLut(newLut: CubeLut?) {
        lut = newLut
        if (newLut == null) return
        if (lutTexture == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            lutTexture = ids[0]
        }
        val buffer = ByteBuffer.allocateDirect(newLut.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(newLut.data).position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        val filter = if (interpolation == LutInterpolation.HARDWARE) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            newLut.size, newLut.size, newLut.size, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, buffer
        )
        GlUtil.checkError("LUT upload")

        val n = newLut.size.toFloat()
        val scale = (n - 1f) / n
        val offset = 0.5f / n
        GLES30.glUseProgram(graded)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(graded, "uLutSize"), newLut.size)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(graded, "uLutScale"), scale, scale, scale)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(graded, "uLutOffset"), offset, offset, offset)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(graded, "uDomainMin"), newLut.domainMin[0], newLut.domainMin[1], newLut.domainMin[2])
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(graded, "uDomainRange"),
            newLut.domainMax[0] - newLut.domainMin[0],
            newLut.domainMax[1] - newLut.domainMin[1],
            newLut.domainMax[2] - newLut.domainMin[2]
        )
        GLES30.glUniform1i(GLES30.glGetUniformLocation(graded, "uInput"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(graded, "uLut"), 1)
    }

    /**
     * Draws [inputTexture] into the [width]×[height] viewport at ([x], [y]). [texMatrix] is the SurfaceTexture transform
     * for camera frames, or null for identity.
     */
    fun draw(inputTexture: Int, width: Int, height: Int, texMatrix: FloatArray? = null, x: Int = 0, y: Int = 0) {
        val useLut = lut != null
        val program = if (useLut) graded else passthrough
        GLES30.glViewport(x, y, width, height)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(if (useLut) texMatrixLocationGraded else texMatrixLocationPassthrough, 1, false, texMatrix ?: identity, 0)
        GLES30.glUniform3fv(if (useLut) gainLocationGraded else gainLocationPassthrough, 1, inputGain, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (input == InputKind.EXTERNAL_OES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, inputTexture)
        if (useLut) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        GLES30.glDeleteProgram(passthrough)
        GLES30.glDeleteProgram(graded)
        if (lutTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        lutTexture = 0
    }

    companion object {
        /** Creates the external texture a SurfaceTexture renders camera frames into. */
        fun createExternalTexture(): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return ids[0]
        }
    }
}
