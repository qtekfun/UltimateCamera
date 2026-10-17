package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.portrait.DiscKernel
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.LivePortraitPlan
import com.qtekfun.ultimatecamera.core.gpu.portrait.PortraitGpuData
import com.qtekfun.ultimatecamera.core.gpu.portrait.PortraitShaders

/**
 * The live portrait preview of [CameraRenderer] (D87), on its GL thread and context: the camera frame is drawn (with
 * the monitor look) into a small sensor-frame copy ([LivePortraitPlan.width] x height), blurred there by the gather
 * shader with the CoC taken straight from the latest [LiveDepth], and the blurred copy is what the preview shows,
 * scaled up. Only the preview: the encoder, proxy and stream never see it. Setup allocates; per frame it does not.
 */
internal class LivePortraitStage(val plan: LivePortraitPlan) {
    private val frame = RenderTarget(plan.width, plan.height, GLES30.GL_RGBA8)
    private val blurred = RenderTarget(plan.width, plan.height, GLES30.GL_RGBA8)
    private val gather = GlUtil.compileProgram(PortraitShaders.VERTEX, PortraitShaders.gather(PortraitShaders.CocSource.LIVE_DEPTH))
    private val present = LutRenderer(InputKind.TEXTURE_2D, LutInterpolation.HARDWARE)
    private val depthTexture: Int
    private val kernel = DiscKernel.LIVE.packed()
    private var depth: LiveDepth? = null
    private var depthSize = 0 to 0

    /** True once a blurred picture exists to show. */
    var hasPicture = false
        private set

    init {
        if (!frame.complete || !blurred.complete) throw GlException("live portrait targets incomplete")
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        depthTexture = ids[0]
        // The blurred copy is scaled up to the screen: filter it.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurred.texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glUseProgram(gather)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gather, "uImage"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gather, "uDepth"), 1)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(gather, "uSize"), plan.width, plan.height)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(gather, "uKernel"), DiscKernel.LIVE.samples, kernel, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gather, "uSamples"), DiscKernel.LIVE.samples)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gather, "uMaxRadius"), plan.maxRadius)
        GlUtil.checkError("live portrait setup")
    }

    /** True when a depth map has arrived (until then the preview stays plain). */
    val hasDepth: Boolean get() = depth != null

    /** Uploads [update] (a few thousand floats: the depth maps are small). Called on a change, never twice per frame. */
    fun setDepth(update: LiveDepth) {
        val plane = update.inverse
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, plane.width, plane.height, 0, GLES30.GL_RED, GLES30.GL_FLOAT,
            PortraitGpuData.floats(
                plane.data
            )
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        depthSize = plane.width to plane.height
        depth = update
        GlUtil.checkError("live depth upload")
    }

    /**
     * Blurs the current camera frame: [drawFrame] draws it into the bound small target (sensor frame, the viewport
     * already set), then the gather pass writes the blurred copy. Leaves the blurred target's framebuffer bound.
     */
    fun blur(drawFrame: (width: Int, height: Int) -> Unit) {
        val d = depth ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frame.framebuffer)
        drawFrame(plan.width, plan.height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurred.framebuffer)
        GLES30.glViewport(0, 0, plan.width, plan.height)
        GLES30.glUseProgram(gather)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(gather, "uDepthSize"), depthSize.first, depthSize.second)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gather, "uFocus"), d.shaderFocus)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gather, "uScale"), d.shaderScale(plan.width))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frame.texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        hasPicture = true
    }

    /** Draws the last blurred copy into the default framebuffer's viewport with the sensor-frame → screen [texMatrix]. */
    fun present(x: Int, y: Int, width: Int, height: Int, texMatrix: FloatArray) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        present.draw(blurred.texture, width, height, texMatrix, x, y)
    }

    fun release() {
        frame.release()
        blurred.release()
        present.release()
        GLES30.glDeleteProgram(gather)
        GLES30.glDeleteTextures(1, intArrayOf(depthTexture), 0)
    }
}
