package com.qtekfun.ultimatecamera.core.gpu.meters

import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.gl.AccumulationFormat
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.gl.EglCore
import com.qtekfun.ultimatecamera.core.gpu.gl.GlCapabilities
import com.qtekfun.ultimatecamera.core.gpu.gl.ScopeRenderer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ScopeRenderer] on the device's GPU against [ScopeMath]. The source is the scope chart upscaled in 4×4 blocks, so the
 * downsample is exact and every later step is integer maths:
 * - analysis image, histogram (every layer, and the asynchronous PBO readback), waveform and vectorscope counts:
 *   **exact** (tolerance 0), with counts clamped to the target's capacity on both sides;
 * - displays: **1/255** (colour × intensity rounding), histogram pixels within 1e-4 of a bar top skipped.
 * Runs in the format the driver offers (half float on most phones) and in the RGBA8 fallback. Logged under UCSPIKE
 * (`METER_SCOPE`).
 */
@RunWith(AndroidJUnit4::class)
class ScopeShaderTest {
    private lateinit var egl: EglCore
    private val w = MeterTestCharts.SCOPE_WIDTH
    private val h = MeterTestCharts.SCOPE_HEIGHT
    private val block = MeterTestCharts.SCOPE_BLOCK
    private val chart = MeterTestCharts.scopeChart()
    private var source = 0
    private val all = ScopeSettings(
        histogram = true,
        waveform = true,
        vectorscope = true,
        analysisDivisor = block,
        updateEveryFrames = 2,
        vectorscopeSize = 128,
        histogramReadback = true
    )

    @Before
    fun setUp() {
        egl = EglCore(EglColorDepth.RGBA8888, recordable = false)
        egl.makeCurrent(egl.createPbufferSurface(16, 16))
        source = GlTestSupport.upload(MeterTestCharts.blockUpscale(chart, w, h, block), w * block, h * block)
    }

    @After
    fun tearDown() {
        GLES30.glDeleteTextures(1, intArrayOf(source), 0)
        egl.release()
    }

    @Test
    fun countsMatchInTheDriversFormat() = checkCounts(GlCapabilities.current())

    @Test
    fun countsMatchInTheRgba8Fallback() = checkCounts(GlCapabilities(3, 0, emptySet()))

    @Test
    fun displaysMatchInTheDriversFormat() = checkDisplays(GlCapabilities.current())

    @Test
    fun displaysMatchInTheRgba8Fallback() = checkDisplays(GlCapabilities(3, 0, emptySet()))

    @Test
    fun updatesOnlyOnDueFrames() {
        val scopes = ScopeRenderer(InputKind.TEXTURE_2D, all, w * block, h * block)
        assertTrue(scopes.update(source, frameIndex = 0))
        assertFalse(scopes.update(source, frameIndex = 1))
        assertTrue(scopes.update(source, frameIndex = 2))
        scopes.configure(all.copy(histogram = false, waveform = false, vectorscope = false, histogramReadback = false), w * block, h * block)
        assertFalse("nothing enabled", scopes.update(source, frameIndex = 4))
        scopes.release()
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    @Test
    fun histogramReadbackIsAsynchronousAndExact() {
        val scopes = ScopeRenderer(InputKind.TEXTURE_2D, all, w * block, h * block)
        val out = IntArray(ScopeMath.LEVELS * 4)
        assertFalse("nothing requested yet", scopes.pollHistogram(out))
        scopes.update(source, frameIndex = 0)
        GLES30.glFinish()
        var got = scopes.pollHistogram(out)
        var tries = 0
        while (!got && tries++ < 50) {
            Thread.sleep(10)
            got = scopes.pollHistogram(out)
        }
        assertTrue("readback never completed", got)
        val layers = scopes.histogramLayers
        assertArrayEquals(ScopeMath.sumLayers(ScopeMath.histogram(chart, layers), layers), out)
        assertFalse("each result is delivered once", scopes.pollHistogram(out))
        scopes.release()
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    @Test
    fun externalOesVariantCompiles() {
        ScopeRenderer(InputKind.EXTERNAL_OES, all, 1920, 1080).release()
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    private fun checkCounts(capabilities: GlCapabilities) {
        for (mode in listOf(WaveformMode.LUMA, WaveformMode.RGB_OVERLAY)) {
            val scopes = ScopeRenderer(InputKind.TEXTURE_2D, all.copy(waveformMode = mode), w * block, h * block, capabilities)
            val format = scopes.format
            assertEquals(w, scopes.analysisWidth)
            assertEquals(h, scopes.analysisHeight)
            assertTrue(scopes.update(source, frameIndex = 0))

            val analysis = IntArray(w * h)
            scopes.readAnalysis(analysis)
            assertArrayEquals("$format analysis image", chart, analysis)

            val layers = scopes.histogramLayers
            assertArrayEquals("$format histogram (all layers)", ScopeMath.histogram(chart, layers), scopes.readCounts(MeterPass.HISTOGRAM))

            val waveform = scopes.readCounts(MeterPass.WAVEFORM)
            val expectedWaveform = ScopeMath.waveform(chart, w, format.capacity)
            val channels = if (mode == WaveformMode.LUMA) intArrayOf(ScopeMath.Y) else intArrayOf(ScopeMath.R, ScopeMath.G, ScopeMath.B)
            for (i in 0 until w * ScopeMath.LEVELS) {
                for (c in channels) assertEquals("$format waveform $mode texel $i channel $c", expectedWaveform[i * 4 + c], waveform[i * 4 + c])
            }

            val vector = scopes.readCounts(MeterPass.VECTORSCOPE)
            val expectedVector = ScopeMath.vectorscope(chart, 128, format.capacity)
            for (i in expectedVector.indices) assertEquals("$format vectorscope cell $i", expectedVector[i], minOf(vector[i * 4], format.capacity))

            Log.i("UCSPIKE", "METER_SCOPE counts format=$format layers=$layers waveform=$mode exact")
            scopes.release()
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        }
    }

    private fun checkDisplays(capabilities: GlCapabilities) {
        val scopes = ScopeRenderer(InputKind.TEXTURE_2D, all, w * block, h * block, capabilities)
        scopes.update(source, frameIndex = 0)
        val format = scopes.format
        val n = scopes.sampleCount
        val layers = scopes.histogramLayers
        for (mode in HistogramMode.entries) {
            val s = all.copy(histogramMode = mode)
            scopes.configure(s, w * block, h * block)
            scopes.update(source, frameIndex = 0)
            val totals = ScopeMath.sumLayers(ScopeMath.histogram(chart, layers), layers)
            val margins = FloatArray(256 * 64)
            val expected = ScopeDisplayMath.histogramDisplay(totals, n, s, 256, 64, margins)
            display("histogram_$mode", format, 256, 64, expected, margins) { scopes.drawHistogram(0, 0, 256, 64) }
        }
        for (mode in WaveformMode.entries) {
            val s = all.copy(waveformMode = mode)
            scopes.configure(s, w * block, h * block)
            scopes.update(source, frameIndex = 0)
            val counts = ScopeMath.waveform(chart, w, format.capacity)
            val expected = ScopeDisplayMath.waveformDisplay(counts, w, h, s, 192, 128)
            display("waveform_$mode", format, 192, 128, expected, null) { scopes.drawWaveform(0, 0, 192, 128) }
        }
        val counts = ScopeMath.vectorscope(chart, 128, format.capacity)
        val expected = ScopeDisplayMath.vectorscopeDisplay(counts, 128, n, all, 128, 128)
        display("vectorscope", format, 128, 128, expected, null) { scopes.drawVectorscope(0, 0, 128, 128) }
        scopes.release()
    }

    private fun display(name: String, format: AccumulationFormat, width: Int, height: Int, expected: IntArray, margins: FloatArray?, draw: () -> Unit) {
        val fbo = GlTestSupport.framebuffer(width, height)
        draw()
        val actual = GlTestSupport.read(width, height)
        GlTestSupport.release(fbo)
        assertEquals("$name: GL error", GLES30.GL_NO_ERROR, GLES30.glGetError())
        val diff = GlTestSupport.compare(expected, actual, 1, margins)
        Log.i("UCSPIKE", "METER_SCOPE display $name format=$format maxDiff=${diff.worst}/255 skippedAmbiguous=${diff.skipped}")
        assertEquals(
            "$name ($format): ${diff.overTolerance} pixels differ by more than 1/255" + GlTestSupport.describe(diff.firstBad, width, expected, actual),
            0,
            diff.overTolerance
        )
    }
}
