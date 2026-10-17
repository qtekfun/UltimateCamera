package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.portrait.DiscKernel
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.PortraitGpuData
import com.qtekfun.ultimatecamera.core.gpu.portrait.PortraitShaders
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoPortraitPlan
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoPortraitShaders

/**
 * The GL objects of one rung of [VideoPortraitPass] (D89) on [CameraRenderer]'s context: the guide, LUT and blurred
 * copies at the plan's size, the gather and composite programs and the depth texture. [record] gives the recording's
 * current renderer (it is replaced when the look changes). Created on a rung change, never per frame.
 */
internal class VideoPortraitStage(private val plan: VideoPortraitPlan, private val record: () -> LutRenderer) : VideoPortraitStageOps {
    private val guide = RenderTarget(plan.width, plan.height, GLES30.GL_RGBA8)
    private val frame = RenderTarget(plan.width, plan.height, GLES30.GL_RGBA8)
    private val blurred = RenderTarget(plan.width, plan.height, GLES30.GL_RGBA8)
    private val plain = LutRenderer(InputKind.EXTERNAL_OES, LutInterpolation.HARDWARE)
    private val gather = GlUtil.compileProgram(PortraitShaders.VERTEX, PortraitShaders.gather(PortraitShaders.CocSource.LIVE_DEPTH))
    private val composite = GlUtil.compileProgram(VideoPortraitShaders.VERTEX, VideoPortraitShaders.COMPOSITE)
    private val depthTexture: Int
    private var depthSize = 0 to 0
    private val gatherDepthSize = GLES30.glGetUniformLocation(gather, "uDepthSize")
    private val gatherFocus = GLES30.glGetUniformLocation(gather, "uFocus")
    private val gatherScale = GLES30.glGetUniformLocation(gather, "uScale")
    private val compositeTex = GLES30.glGetUniformLocation(composite, "uTexMatrix")
    private val compositeDepthSize = GLES30.glGetUniformLocation(composite, "uDepthSize")
    private val compositeFocus = GLES30.glGetUniformLocation(composite, "uFocus")
    private val compositeScale = GLES30.glGetUniformLocation(composite, "uScale")

    init {
        if (!guide.complete || !frame.complete || !blurred.complete) throw GlException("video portrait targets incomplete")
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        depthTexture = ids[0]
        // The blurred copy is scaled up to the frame: filter it.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurred.texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val kernel = DiscKernel(plan.samples)
        GLES30.glUseProgram(gather)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gather, "uImage"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gather, "uDepth"), 1)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(gather, "uSize"), plan.width, plan.height)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(gather, "uKernel"), kernel.samples, kernel.packed(), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gather, "uSamples"), kernel.samples)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gather, "uMaxRadius"), plan.maxRadius)
        GLES30.glUseProgram(composite)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(composite, "uCamera"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(composite, "uBlurred"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(composite, "uGuide"), 2)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(composite, "uDepth"), 3)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(composite, "uLowSize"), plan.width, plan.height)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(composite, "uMaxRadius"), plan.maxRadius)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(composite, "uSigma"), VideoPortraitShaders.GUIDE_SIGMA)
        GlUtil.checkError("video portrait setup")
    }

    /** Uploads the depth map (a few thousand floats), on a change only. */
    override fun uploadDepth(update: LiveDepth) {
        val plane = update.inverse
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, plane.width, plane.height, 0, GLES30.GL_RED, GLES30.GL_FLOAT, PortraitGpuData.floats(plane.data)
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        depthSize = plane.width to plane.height
        GlUtil.checkError("video depth upload")
    }

    /** The guide and LUT copies at the reduced size, then the gather into [blurred]. */
    override fun prepare(cameraTexture: Int, texMatrix: FloatArray, d: LiveDepth) {
        try {
            reduced(cameraTexture, texMatrix, d)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    private fun reduced(cameraTexture: Int, texMatrix: FloatArray, d: LiveDepth) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, guide.framebuffer)
        plain.draw(cameraTexture, plan.width, plan.height, texMatrix)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frame.framebuffer)
        record().draw(cameraTexture, plan.width, plan.height, texMatrix)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurred.framebuffer)
        GLES30.glViewport(0, 0, plan.width, plan.height)
        GLES30.glUseProgram(gather)
        GLES30.glUniform2i(gatherDepthSize, depthSize.first, depthSize.second)
        GLES30.glUniform1f(gatherFocus, d.shaderFocus)
        GLES30.glUniform1f(gatherScale, d.shaderScale(plan.width))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frame.texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** The edge-aware blend of [blurred] over the sharp frame in the bound default framebuffer. */
    override fun composite(cameraTexture: Int, texMatrix: FloatArray, width: Int, height: Int, d: LiveDepth) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(composite)
        GLES30.glUniformMatrix4fv(compositeTex, 1, false, texMatrix, 0)
        GLES30.glUniform2i(compositeDepthSize, depthSize.first, depthSize.second)
        GLES30.glUniform1f(compositeFocus, d.shaderFocus)
        GLES30.glUniform1f(compositeScale, d.shaderScale(plan.width))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurred.texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, guide.texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture)
        GLES30.glEnable(GLES30.GL_BLEND)
        try {
            // Colour mixes by the blur's alpha; the surface keeps its own alpha (opaque video).
            GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        } finally {
            // The other passes of the renderer draw opaque.
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
    }

    override fun release() {
        guide.release()
        frame.release()
        blurred.release()
        plain.release()
        GLES30.glDeleteProgram(gather)
        GLES30.glDeleteProgram(composite)
        GLES30.glDeleteTextures(1, intArrayOf(depthTexture), 0)
    }
}
