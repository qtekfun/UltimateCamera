package com.qtekfun.ultimatecamera.core.gpu.meters

import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.gl.EglCore
import com.qtekfun.ultimatecamera.core.gpu.gl.MonitorOverlayRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every pass of [MonitorOverlayRenderer] on the device's GPU against [MonitorOverlayMath] (pinned to PNGs by the JVM
 * tests). Tolerances, RGB channels of an RGBA8 target:
 * - peaking, zebras, false colour, guides, passthrough: **1/255**, after skipping the pixels whose decision value lies
 *   within 1e-4 of its threshold (float rounding may legitimately flip them; the count is logged and must stay < 1 %);
 * - desqueeze (bilinear resampling between texels): **3/255**, the slack for 8-bit texture-filter weights.
 * Results are logged under the UCSPIKE tag (`METER_OVERLAY`).
 */
@RunWith(AndroidJUnit4::class)
class MonitorOverlayShaderTest {
    private lateinit var egl: EglCore
    private lateinit var renderer: MonitorOverlayRenderer
    private val w = MeterTestCharts.OVERLAY_WIDTH
    private val h = MeterTestCharts.OVERLAY_HEIGHT
    private val chart = MeterTestCharts.overlayChart()
    private var source = 0

    @Before
    fun setUp() {
        egl = EglCore(EglColorDepth.RGBA8888, recordable = false)
        egl.makeCurrent(egl.createPbufferSurface(16, 16))
        renderer = MonitorOverlayRenderer(InputKind.TEXTURE_2D)
        source = GlTestSupport.upload(chart, w, h)
    }

    @After
    fun tearDown() {
        GLES30.glDeleteTextures(1, intArrayOf(source), 0)
        renderer.release()
        egl.release()
    }

    @Test
    fun passthrough() = check("passthrough", MonitorOverlaySettings(), tolerance = 0)

    @Test
    fun peaking() = check("peaking", MeterReferenceSettings.peaking)

    @Test
    fun zebrasStaticAndAnimated() {
        check("zebras_f0", MeterReferenceSettings.zebras, frameIndex = 0)
        check("zebras_f3", MeterReferenceSettings.zebras, frameIndex = 3)
        check("zebras_f6", MeterReferenceSettings.zebras, frameIndex = 6)
    }

    @Test
    fun falseColor() = check("false_color", MeterReferenceSettings.falseColor)

    @Test
    fun guides() = check("guides", MeterReferenceSettings.guides)

    @Test
    fun everythingAtOnce() = check("all", MeterReferenceSettings.all, frameIndex = 5)

    @Test
    fun desqueezeEveryFactor() {
        for (d in Desqueeze.entries) {
            check(
                "desqueeze_${d.name}",
                MonitorOverlaySettings(desqueeze = d, guides = MeterReferenceSettings.guides.guides),
                tolerance = 3
            )
        }
    }

    /** The overlay drawn into a sub-viewport leaves the pixels around it alone and lands where the CPU says. */
    @Test
    fun viewportOffsetIsHonoured() {
        val fbo = GlTestSupport.framebuffer(w + 20, h + 10)
        GLES30.glClearColor(0.5f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        renderer.setSettings(MeterReferenceSettings.all)
        renderer.draw(source, w, h, w, h, frameIndex = 5, x = 12, y = 7)
        val out = GlTestSupport.read(w + 20, h + 10)
        GlTestSupport.release(fbo)
        val margins = FloatArray(w * h)
        val expected = MonitorOverlayMath.render(chart, w, h, w, h, MeterReferenceSettings.all, 5, margins)
        val inner = IntArray(w * h) { i -> out[(i / w + 7) * (w + 20) + i % w + 12] }
        val diff = GlTestSupport.compare(expected, inner, 1, margins)
        assertEquals("pixels over tolerance", 0, diff.overTolerance)
        assertEquals("outside the viewport untouched", 0xFF800000.toInt(), out[0])
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    /** The camera variant must at least compile and link on this driver (it is exercised by the camera screen). */
    @Test
    fun externalOesVariantCompiles() {
        MonitorOverlayRenderer(InputKind.EXTERNAL_OES).release()
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    private fun check(name: String, settings: MonitorOverlaySettings, frameIndex: Long = 0, tolerance: Int = 1) {
        val fbo = GlTestSupport.framebuffer(w, h)
        renderer.setSettings(settings)
        renderer.draw(source, w, h, w, h, frameIndex = frameIndex)
        val actual = GlTestSupport.read(w, h)
        GlTestSupport.release(fbo)
        assertEquals("$name: GL error", GLES30.GL_NO_ERROR, GLES30.glGetError())
        val margins = FloatArray(w * h)
        val expected = MonitorOverlayMath.render(chart, w, h, w, h, settings, frameIndex, margins)
        val diff = GlTestSupport.compare(expected, actual, tolerance, margins)
        Log.i("UCSPIKE", "METER_OVERLAY $name maxDiff=${diff.worst}/255 over=${diff.overTolerance} skippedAmbiguous=${diff.skipped}")
        assertTrue("$name: ${diff.skipped} ambiguous pixels", diff.skipped < w * h / 100)
        assertEquals(
            "$name: ${diff.overTolerance} pixels differ by more than $tolerance/255 (worst ${diff.worst})" +
                GlTestSupport.describe(diff.firstBad, w, expected, actual),
            0,
            diff.overTolerance
        )
    }
}
