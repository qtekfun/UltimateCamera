package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.LutShaders
import com.qtekfun.ultimatecamera.core.gpu.meters.HistogramMode
import com.qtekfun.ultimatecamera.core.gpu.meters.MeterPass
import com.qtekfun.ultimatecamera.core.gpu.meters.MeterShaders
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeBins
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeDisplayMath
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeMath
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.WaveformMode

/** How scope counts are accumulated, chosen from [GlCapabilities]. */
enum class AccumulationFormat(val internalFormat: Int, val capacity: Int, val increment: Float, val unit: Float) {
    /** RGBA16F: integer counts are exact up to 2048 per texel. */
    HALF_FLOAT(GLES30.GL_RGBA16F, 2048, 1f, 1f),

    /** RGBA8 fallback for GLES 3.0 without float colour buffers: 255 per texel, each sample adds 1/255. */
    UNORM8(GLES30.GL_RGBA8, 255, 1f / 255f, 255f)
}

/**
 * Histogram, waveform and vectorscope computed on the GPU at reduced resolution and rate (REQ-MON-001, REQ-MON-002).
 *
 * [update] (every [ScopeSettings.updateEveryFrames] frames, otherwise a no-op):
 * 1. renders the monitored image into a small RGBA8 analysis image (`ScopeSettings.analysisSize`, one bilinear tap per
 *    cell);
 * 2. **scatters** the analysis samples as 1-pixel points with additive blending into tiny count textures: the vertex
 *    shader reads its sample with `texelFetch` (vertex texture fetch, core GLES 3.0), bins it with exact integer maths
 *    and places the point on its bin. No vertex buffers (`gl_VertexID`, instancing per channel), no compute shaders,
 *    no atomics: it runs the same on GLES 3.0, 3.1 and 3.2 drivers. A compute-shader path is not worth a second code
 *    path until a phone shows the scatter is too slow (the cost hook in [GpuTimer] tells).
 * 3. optionally copies the histogram into a pixel-pack buffer with a fence ([pollHistogram] maps it frames later, only
 *    once the fence has signalled: no stall on the render thread).
 * The draw functions then show the last counts every frame (one small quad each, premultiplied over a dark panel).
 *
 * Sizes: at 1/8 of a 1080p preview there are 32 400 samples; the histogram target is 256 × 16 texels (half float) and
 * the waveform 240 × 256. Per frame and per update nothing is allocated and nothing is read back synchronously; the only
 * allocation is the JNI wrapper `glMapBufferRange` returns, once per histogram readback (not per frame).
 *
 * GL state after any call: blending and scissor disabled, `GL_FRAMEBUFFER` bound to `restoreFramebuffer` after
 * [update], viewport as set by the call (rebind/re-set yours), clear colour transparent black. Create, use and [release]
 * on the GL thread. The camera screen creates it only while `FeatureGroup.METERS` is enabled.
 */
class ScopeRenderer(
    private val input: InputKind,
    settings: ScopeSettings,
    sourceWidth: Int,
    sourceHeight: Int,
    capabilities: GlCapabilities = GlCapabilities.current(),
    private val timer: GpuTimer? = null
) {
    private val preferredFormat = if (capabilities.halfFloatColorBuffer) AccumulationFormat.HALF_FLOAT else AccumulationFormat.UNORM8

    /** The format in use (half float unless the driver refused it). */
    var format = preferredFormat
        private set

    private val downsample = GlUtil.compileProgram(LutShaders.VERTEX, LutShaders.fragment(input, lut = null))
    private val histogram = Scatter(MeterShaders.HISTOGRAM_VERTEX)
    private val waveform = Scatter(MeterShaders.WAVEFORM_VERTEX)
    private val vectorscope = Scatter(MeterShaders.VECTORSCOPE_VERTEX)
    private val histogramDisplay = Display(MeterShaders.HISTOGRAM_DISPLAY, "uLayers", "uScale", "uLumaMode")
    private val waveformDisplay = Display(MeterShaders.WAVEFORM_DISPLAY, "uGain", "uMode", "uAnalysisWidth")
    private val vectorscopeDisplay = Display(MeterShaders.VECTORSCOPE_DISPLAY, "uGain", "uGrid")
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val uDownsampleMatrix = GLES30.glGetUniformLocation(downsample, "uTexMatrix")

    var settings: ScopeSettings = settings
        private set
    var analysisWidth = 0
        private set
    var analysisHeight = 0
        private set
    val sampleCount: Int get() = analysisWidth * analysisHeight

    /** Histogram layers (see [ScopeBins.histogramLayers]). */
    var histogramLayers = 1
        private set

    private var analysis: RenderTarget? = null
    private var histogramTarget: RenderTarget? = null
    private var waveformTarget: RenderTarget? = null
    private var vectorscopeTarget: RenderTarget? = null
    private var readback: HistogramReadback? = null
    private var hasData = false

    init {
        GLES30.glUseProgram(downsample)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(downsample, "uInput"), 0)
        configure(settings, sourceWidth, sourceHeight)
    }

    /** Applies new settings or a new source size; recreates the small targets only when their sizes change. */
    fun configure(newSettings: ScopeSettings, sourceWidth: Int, sourceHeight: Int) {
        val (w, h) = newSettings.analysisSize(sourceWidth, sourceHeight)
        val resize = w != analysisWidth || h != analysisHeight || newSettings.vectorscopeSize != settings.vectorscopeSize || analysis == null
        settings = newSettings
        // Counts of a scope that was off, or in another mode, are stale: nothing is drawn until the next update.
        hasData = false
        if (resize) {
            releaseTargets()
            analysisWidth = w
            analysisHeight = h
            createTargets()
        }
        if (newSettings.histogramReadback && readback == null) {
            readback = HistogramReadback(histogramTarget!!, histogramLayers, format)
        } else if (!newSettings.histogramReadback) {
            readback?.release()
            readback = null
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Recomputes the scopes from [inputTexture] when [frameIndex] is due (`frameIndex % updateEveryFrames == 0`) and a
     * scope is enabled. Returns whether it ran. Binds [restoreFramebuffer] on return.
     */
    fun update(inputTexture: Int, texMatrix: FloatArray? = null, frameIndex: Long = 0, restoreFramebuffer: Int = 0): Boolean {
        val s = settings
        if (!s.anyEnabled || frameIndex % s.updateEveryFrames != 0L) return false
        val source = analysis ?: return false

        timer?.begin(MeterPass.SCOPE_DOWNSAMPLE.ordinal)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, source.framebuffer)
        GLES30.glViewport(0, 0, analysisWidth, analysisHeight)
        GLES30.glUseProgram(downsample)
        GLES30.glUniformMatrix4fv(uDownsampleMatrix, 1, false, texMatrix ?: identity, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (input == InputKind.EXTERNAL_OES) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        timer?.end(MeterPass.SCOPE_DOWNSAMPLE.ordinal)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source.texture)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        if (s.histogram || s.histogramReadback) {
            // Luma only when that is all that is shown; all four channels when the CPU reads them back.
            val luma = !s.histogramReadback && s.histogramMode == HistogramMode.LUMA
            val channels = if (s.histogramReadback) {
                4
            } else if (luma) {
                1
            } else {
                3
            }
            histogram.run(histogramTarget!!, if (luma) ScopeMath.Y else 0, channels, MeterPass.HISTOGRAM)
            readback?.request()
        }
        if (s.waveform) {
            val luma = s.waveformMode == WaveformMode.LUMA
            waveform.run(waveformTarget!!, if (luma) ScopeMath.Y else 0, if (luma) 1 else 3, MeterPass.WAVEFORM)
        }
        if (s.vectorscope) vectorscope.run(vectorscopeTarget!!, 0, 1, MeterPass.VECTORSCOPE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, restoreFramebuffer)
        hasData = true
        return true
    }

    /**
     * Copies the most recent finished histogram readback into [out] (`LEVELS × 4` counts, index `level × 4 + channel`,
     * channels R, G, B, Y). Never waits: returns false when no new result is ready. Needs `histogramReadback`.
     */
    fun pollHistogram(out: IntArray): Boolean = readback?.poll(out) ?: false

    fun drawHistogram(x: Int, y: Int, width: Int, height: Int) {
        val target = histogramTarget ?: return
        if (!hasData || !settings.histogram) return
        histogramDisplay.begin(target, x, y, width, height)
        GLES30.glUniform1i(histogramDisplay.extra[0], histogramLayers)
        GLES30.glUniform1f(histogramDisplay.extra[1], ScopeMath.histogramHeightScale(sampleCount, settings))
        GLES30.glUniform1i(histogramDisplay.extra[2], if (settings.histogramMode == HistogramMode.LUMA) 1 else 0)
        histogramDisplay.end()
    }

    fun drawWaveform(x: Int, y: Int, width: Int, height: Int) {
        val target = waveformTarget ?: return
        if (!hasData || !settings.waveform) return
        waveformDisplay.begin(target, x, y, width, height)
        GLES30.glUniform1f(waveformDisplay.extra[0], ScopeMath.waveformGain(analysisHeight, settings))
        GLES30.glUniform1i(waveformDisplay.extra[1], settings.waveformMode.ordinal)
        GLES30.glUniform1i(waveformDisplay.extra[2], analysisWidth)
        waveformDisplay.end()
    }

    fun drawVectorscope(x: Int, y: Int, width: Int, height: Int) {
        val target = vectorscopeTarget ?: return
        if (!hasData || !settings.vectorscope) return
        vectorscopeDisplay.begin(target, x, y, width, height)
        GLES30.glUniform1f(vectorscopeDisplay.extra[0], ScopeMath.vectorscopeGain(sampleCount, settings.vectorscopeSize, settings))
        GLES30.glUniform1i(vectorscopeDisplay.extra[1], settings.vectorscopeSize)
        vectorscopeDisplay.end()
    }

    fun release() {
        releaseTargets()
        GLES30.glDeleteProgram(downsample)
        listOf(histogram.program, waveform.program, vectorscope.program).forEach { GLES30.glDeleteProgram(it) }
        listOf(histogramDisplay.program, waveformDisplay.program, vectorscopeDisplay.program).forEach { GLES30.glDeleteProgram(it) }
    }

    /** Test hook: the analysis image, read synchronously (RGBA8, GL row order). */
    internal fun readAnalysis(out: IntArray) {
        val target = analysis ?: return
        val bytes = java.nio.ByteBuffer.allocateDirect(analysisWidth * analysisHeight * 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, analysisWidth, analysisHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        for (i in out.indices) {
            val r = bytes.get(i * 4).toInt() and 0xFF
            val g = bytes.get(i * 4 + 1).toInt() and 0xFF
            val b = bytes.get(i * 4 + 2).toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Test hook: the raw counts of a target, read synchronously as integers (4 per texel, GL row order). */
    internal fun readCounts(kind: MeterPass): IntArray {
        val target = when (kind) {
            MeterPass.HISTOGRAM -> histogramTarget
            MeterPass.WAVEFORM -> waveformTarget
            MeterPass.VECTORSCOPE -> vectorscopeTarget
            else -> null
        } ?: error("no counts for $kind")
        val out = IntArray(target.width * target.height * 4)
        CountReader(target, format).readBlocking(out)
        return out
    }

    private fun createTargets() {
        format = preferredFormat
        histogramLayers = ScopeBins.histogramLayers(sampleCount, format.capacity)
        analysis = RenderTarget(analysisWidth, analysisHeight, GLES30.GL_RGBA8)
        var targets = countTargets()
        if (targets.any { !it.complete } && format == AccumulationFormat.HALF_FLOAT) {
            targets.forEach { it.release() }
            format = AccumulationFormat.UNORM8
            histogramLayers = ScopeBins.histogramLayers(sampleCount, format.capacity)
            targets = countTargets()
        }
        check(targets.all { it.complete }) { "scope targets incomplete in $format" }
        histogramTarget = targets[0]
        waveformTarget = targets[1]
        vectorscopeTarget = targets[2]
        listOf(histogram, waveform, vectorscope).forEach { it.setup() }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun countTargets() = listOf(
        RenderTarget(ScopeMath.LEVELS, histogramLayers, format.internalFormat),
        RenderTarget(analysisWidth, ScopeMath.LEVELS, format.internalFormat),
        RenderTarget(settings.vectorscopeSize, settings.vectorscopeSize, format.internalFormat)
    )

    private fun releaseTargets() {
        readback?.release()
        readback = null
        listOfNotNull(analysis, histogramTarget, waveformTarget, vectorscopeTarget).forEach { it.release() }
        analysis = null
        histogramTarget = null
        waveformTarget = null
        vectorscopeTarget = null
    }

    /** One scatter program; uniforms that depend on sizes are set in [setup], per run only the channel range. */
    private inner class Scatter(vertex: String) {
        val program = GlUtil.compileProgram(vertex, MeterShaders.SCATTER_FRAGMENT)
        private val uFirstChannel = GLES30.glGetUniformLocation(program, "uFirstChannel")

        fun setup() {
            GLES30.glUseProgram(program)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uAnalysis"), 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uWidth"), analysisWidth)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLayers"), histogramLayers)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uSize"), settings.vectorscopeSize)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uIncrement"), format.increment)
        }

        fun run(target: RenderTarget, firstChannel: Int, channels: Int, pass: MeterPass) {
            timer?.begin(pass.ordinal)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
            GLES30.glViewport(0, 0, target.width, target.height)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(program)
            GLES30.glUniform1i(uFirstChannel, firstChannel)
            GLES30.glDrawArraysInstanced(GLES30.GL_POINTS, 0, sampleCount, channels)
            timer?.end(pass.ordinal)
        }
    }

    /** One display program drawn as a premultiplied quad over the current framebuffer. */
    private inner class Display(fragment: String, vararg uniforms: String) {
        val program = GlUtil.compileProgram(MeterShaders.QUAD_VERTEX, fragment)

        /** Locations of the display's own uniforms, in constructor order (looked up once, not per frame). */
        val extra = IntArray(uniforms.size) { GLES30.glGetUniformLocation(program, uniforms[it]) }
        private val uOrigin = GLES30.glGetUniformLocation(program, "uOrigin")
        private val uSize = GLES30.glGetUniformLocation(program, "uSize")
        private val uUnit = GLES30.glGetUniformLocation(program, "uUnit")
        private val uBackground = GLES30.glGetUniformLocation(program, "uBackground")

        init {
            GLES30.glUseProgram(program)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCounts"), 0)
            val colors =
                intArrayOf(
                    ScopeDisplayMath.COLOR_RED,
                    ScopeDisplayMath.COLOR_GREEN,
                    ScopeDisplayMath.COLOR_BLUE,
                    ScopeDisplayMath.COLOR_LUMA,
                    ScopeDisplayMath.COLOR_VECTOR
                )
            val rgb = FloatArray(colors.size * 3) { i -> ((colors[i / 3] shr (16 - (i % 3) * 8)) and 0xFF) / 255f }
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uColors"), colors.size, rgb, 0)
        }

        fun begin(target: RenderTarget, x: Int, y: Int, width: Int, height: Int) {
            timer?.begin(MeterPass.SCOPE_DISPLAY.ordinal)
            GLES30.glViewport(x, y, width, height)
            GLES30.glUseProgram(program)
            GLES30.glUniform2i(uOrigin, x, y)
            GLES30.glUniform2i(uSize, width, height)
            GLES30.glUniform1f(uUnit, format.unit)
            GLES30.glUniform1f(uBackground, settings.backgroundOpacity)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target.texture)
        }

        fun end() {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
            GLES30.glBlendFuncSeparate(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ZERO, GLES30.GL_ONE)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glDisable(GLES30.GL_BLEND)
            timer?.end(MeterPass.SCOPE_DISPLAY.ordinal)
        }
    }
}
