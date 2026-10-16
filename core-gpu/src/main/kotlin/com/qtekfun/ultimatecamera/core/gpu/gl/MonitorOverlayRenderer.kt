package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.LutShaders
import com.qtekfun.ultimatecamera.core.gpu.meters.GuideLayout
import com.qtekfun.ultimatecamera.core.gpu.meters.GuideRects
import com.qtekfun.ultimatecamera.core.gpu.meters.MeterPass
import com.qtekfun.ultimatecamera.core.gpu.meters.MeterShaders
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorGeometry
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorOverlayMath
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorOverlaySettings
import com.qtekfun.ultimatecamera.core.gpu.meters.PixelRect

/**
 * Draws the monitor image with its aids into the **preview / monitor surface only**: the recording path (LUT pass into
 * the encoder surface) never goes through this renderer, so nothing here is burned into a clip.
 *
 * Input is the monitored image: ideally the LUT pass rendered into a preview-sized texture ([InputKind.TEXTURE_2D]),
 * or the camera texture directly when no LUT is used ([InputKind.EXTERNAL_OES] with its SurfaceTexture matrix).
 * [MonitorOverlayMath] is the CPU specification of the output.
 *
 * Per frame, chosen for modest phones:
 * - one full-screen pass over the content rectangle doing passthrough + false colour + two zebras + peaking. Peaking
 *   costs 4 extra texture fetches per pixel and only when enabled (uniform branch, coherent across the screen);
 * - guides: one instanced draw of at most [GuideLayout.MAX_RECTS] thin rectangles, so only the covered pixels are shaded;
 * - letterbox (desqueeze or a different aspect): a scissored clear, free on tile-based GPUs.
 * No allocations, no GL object creation and no `glGet*` per frame; the guide layout is recomputed only when the
 * settings or the sizes change.
 *
 * GL state on return: viewport = the full viewport passed to [draw], blending and scissor disabled, the overlay or guide
 * program current. It expects depth/stencil tests off. Create, use and [release] on the GL thread. The camera screen
 * creates it only while `FeatureGroup.METERS` is enabled.
 */
class MonitorOverlayRenderer(private val input: InputKind, private val timer: GpuTimer? = null) {
    private val overlay = GlUtil.compileProgram(LutShaders.VERTEX, MeterShaders.overlayFragment(input))
    private val guides = GlUtil.compileProgram(MeterShaders.GUIDE_VERTEX, MeterShaders.GUIDE_FRAGMENT)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val guideRects = GuideRects()
    private val rectData = FloatArray(GuideLayout.MAX_RECTS * 4)
    private val rectColors = FloatArray(GuideLayout.MAX_RECTS * 4)
    private val bandRanges = FloatArray(16)
    private val bandColors = FloatArray(24)

    private val uTexMatrix = GLES30.glGetUniformLocation(overlay, "uTexMatrix")
    private val uStepX = GLES30.glGetUniformLocation(overlay, "uStepX")
    private val uStepY = GLES30.glGetUniformLocation(overlay, "uStepY")
    private val uPhase = GLES30.glGetUniformLocation(overlay, "uPhase")
    private val uOrigin = GLES30.glGetUniformLocation(overlay, "uOrigin")
    private val uContentHeight = GLES30.glGetUniformLocation(overlay, "uContentHeight")
    private val uViewport = GLES30.glGetUniformLocation(guides, "uViewport")

    private var settings = MonitorOverlaySettings()
    private var layoutValid = false

    /** Source and viewport sizes the layout was computed for, packed in 16 bits each. */
    private var layoutKey = 0L
    private var content = PixelRect(0, 0, 1, 1)

    init {
        GLES30.glUseProgram(overlay)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(overlay, "uInput"), 0)
        setSettings(settings)
    }

    val currentSettings: MonitorOverlaySettings get() = settings

    /** Applies new settings (uniform uploads). Call when the user changes something, not per frame. */
    fun setSettings(newSettings: MonitorOverlaySettings) {
        settings = newSettings
        layoutValid = false
        val s = newSettings
        GLES30.glUseProgram(overlay)
        var flags = 0
        if (s.peaking.enabled) flags = flags or 1
        if (s.zebras.primary.enabled) flags = flags or 2
        if (s.zebras.secondary.enabled) flags = flags or 4
        if (s.falseColor.enabled) flags = flags or 8
        GLES30.glUniform1i(GLES30.glGetUniformLocation(overlay, "uFlags"), flags)
        uniformColor(overlay, "uPeakColor", s.peaking.color)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(overlay, "uPeakThreshold"), s.peaking.threshold)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(overlay, "uZebraA"), s.zebras.primary.minPercent / 100f, s.zebras.primary.maxPercent / 100f)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(overlay, "uZebraB"), s.zebras.secondary.minPercent / 100f, s.zebras.secondary.maxPercent / 100f)
        uniformColor(overlay, "uZebraAColor", s.zebras.primary.color)
        uniformColor(overlay, "uZebraBColor", s.zebras.secondary.color)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(overlay, "uStripe"), s.zebras.stripeWidthPx)
        val bands = s.falseColor.bands
        for (i in bands.indices) {
            bandRanges[i * 2] = bands[i].minPercent / 100f
            bandRanges[i * 2 + 1] = bands[i].maxPercent / 100f
            bandColors[i * 3] = ((bands[i].color shr 16) and 0xFF) / 255f
            bandColors[i * 3 + 1] = ((bands[i].color shr 8) and 0xFF) / 255f
            bandColors[i * 3 + 2] = (bands[i].color and 0xFF) / 255f
        }
        GLES30.glUniform1i(GLES30.glGetUniformLocation(overlay, "uBandCount"), bands.size)
        if (bands.isNotEmpty()) {
            GLES30.glUniform2fv(GLES30.glGetUniformLocation(overlay, "uBandRange"), bands.size, bandRanges, 0)
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(overlay, "uBandColor"), bands.size, bandColors, 0)
        }
    }

    /**
     * Draws [inputTexture] ([sourceWidth]×[sourceHeight] as displayed, i.e. after [texMatrix]) with the overlays into the
     * [viewWidth]×[viewHeight] viewport at ([x], [y]) of the current framebuffer. [frameIndex] drives animated zebras.
     */
    @Suppress("LongParameterList")
    fun draw(
        inputTexture: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        texMatrix: FloatArray? = null,
        frameIndex: Long = 0,
        x: Int = 0,
        y: Int = 0
    ) {
        updateLayout(sourceWidth, sourceHeight, viewWidth, viewHeight)
        val c = content
        if (c.width != viewWidth || c.height != viewHeight) {
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(x, y, viewWidth, viewHeight)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        }

        timer?.begin(MeterPass.OVERLAY.ordinal)
        val m = texMatrix ?: identity
        GLES30.glViewport(x + c.x, y + c.y, c.width, c.height)
        GLES30.glUseProgram(overlay)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, m, 0)
        // One source texel along each displayed axis, carried through the texture transform.
        GLES30.glUniform2f(uStepX, m[0] / sourceWidth, m[1] / sourceWidth)
        GLES30.glUniform2f(uStepY, m[4] / sourceHeight, m[5] / sourceHeight)
        GLES30.glUniform1i(uPhase, MonitorOverlayMath.stripePhase(settings.zebras, frameIndex))
        GLES30.glUniform2i(uOrigin, x + c.x, y + c.y)
        GLES30.glUniform1i(uContentHeight, c.height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (input == InputKind.EXTERNAL_OES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        timer?.end(MeterPass.OVERLAY.ordinal)

        GLES30.glViewport(x, y, viewWidth, viewHeight)
        if (guideRects.count > 0) {
            timer?.begin(MeterPass.GUIDES.ordinal)
            GLES30.glUseProgram(guides)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, guideRects.count)
            GLES30.glDisable(GLES30.GL_BLEND)
            timer?.end(MeterPass.GUIDES.ordinal)
        }
    }

    fun release() {
        GLES30.glDeleteProgram(overlay)
        GLES30.glDeleteProgram(guides)
    }

    private fun updateLayout(srcW: Int, srcH: Int, viewW: Int, viewH: Int) {
        val key = (srcW.toLong() shl 48) or (srcH.toLong() shl 32) or (viewW.toLong() shl 16) or viewH.toLong()
        if (layoutValid && key == layoutKey) return
        layoutKey = key
        content = MonitorGeometry.contentRect(viewW, viewH, srcW, srcH, settings.desqueeze)
        GuideLayout.layout(settings.guides, content, guideRects)
        for (i in 0 until guideRects.count) {
            for (k in 0..3) rectData[i * 4 + k] = guideRects.rects[i * 4 + k].toFloat()
            val color = guideRects.colors[i]
            rectColors[i * 4] = ((color shr 16) and 0xFF) / 255f
            rectColors[i * 4 + 1] = ((color shr 8) and 0xFF) / 255f
            rectColors[i * 4 + 2] = (color and 0xFF) / 255f
            rectColors[i * 4 + 3] = ((color ushr 24) and 0xFF) / 255f
        }
        GLES30.glUseProgram(guides)
        GLES30.glUniform2f(uViewport, viewW.toFloat(), viewH.toFloat())
        if (guideRects.count > 0) {
            GLES30.glUniform4fv(GLES30.glGetUniformLocation(guides, "uRects"), guideRects.count, rectData, 0)
            GLES30.glUniform4fv(GLES30.glGetUniformLocation(guides, "uColors"), guideRects.count, rectColors, 0)
        }
        layoutValid = true
    }

    private fun uniformColor(program: Int, name: String, argb: Int) {
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, name),
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f
        )
    }
}
