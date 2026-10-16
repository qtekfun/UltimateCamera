package com.qtekfun.ultimatecamera.core.gpu.meters

import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.gl.EglCore
import com.qtekfun.ultimatecamera.core.gpu.gl.GlCapabilities
import com.qtekfun.ultimatecamera.core.gpu.gl.GpuTimer
import com.qtekfun.ultimatecamera.core.gpu.gl.MonitorOverlayRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.ScopeRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cost of the meters on this phone, measured with [GpuTimer] (GPU timer queries when the driver has them, otherwise
 * glFinish + wall clock): a 1080p preview with every overlay on, plus all three scopes at 1/8 resolution. Logged under
 * UCSPIKE as `METER_COST pass=… meanMs=… p95Ms=… maxMs=…` for the spike report; only sanity is asserted, the numbers
 * are the result. Budget reference: the 4K60 LUT pass already takes ~8 ms of the 16.7 ms frame on Pixel 8 / SM8850.
 */
@RunWith(AndroidJUnit4::class)
class MeterCostBenchmarkTest {
    private lateinit var egl: EglCore
    private val previewWidth = 1920
    private val previewHeight = 1080

    @Before
    fun setUp() {
        egl = EglCore(EglColorDepth.RGBA8888, recordable = false)
        egl.makeCurrent(egl.createPbufferSurface(16, 16))
    }

    @After
    fun tearDown() = egl.release()

    @Test
    fun timerMeasuresAPass() {
        val timer = GpuTimer(MeterPass.entries.size, cpuFallback = true)
        Log.i("UCSPIKE", "METER_COST timer mode=${timer.mode}")
        val renderer = MonitorOverlayRenderer(InputKind.TEXTURE_2D, timer)
        val source = GlTestSupport.upload(MeterTestCharts.overlayChart(), MeterTestCharts.OVERLAY_WIDTH, MeterTestCharts.OVERLAY_HEIGHT)
        val fbo = GlTestSupport.framebuffer(previewWidth, previewHeight)
        renderer.setSettings(MeterReferenceSettings.all)
        repeat(20) {
            renderer.draw(source, MeterTestCharts.OVERLAY_WIDTH, MeterTestCharts.OVERLAY_HEIGHT, previewWidth, previewHeight, frameIndex = it.toLong())
            GLES30.glFinish()
            timer.poll()
        }
        GLES30.glFinish()
        timer.poll()
        val summary = timer.summary(MeterPass.OVERLAY.ordinal)
        assertNotNull("no overlay sample in mode ${timer.mode} (skipped ${timer.skipped})", summary)
        assertTrue("non-negative cost", summary!!.meanMs >= 0f)
        GlTestSupport.release(fbo)
        GLES30.glDeleteTextures(1, intArrayOf(source), 0)
        renderer.release()
        timer.release()
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    @Test
    fun costOfEveryMeterAt1080p() {
        val caps = GlCapabilities.current()
        val timer = GpuTimer(MeterPass.entries.size, cpuFallback = true, capabilities = caps)
        val chart = MeterTestCharts.blockUpscale(MeterTestCharts.overlayChart(), MeterTestCharts.OVERLAY_WIDTH, MeterTestCharts.OVERLAY_HEIGHT, 10)
        val source = GlTestSupport.upload(chart, previewWidth, previewHeight)
        val overlay = MonitorOverlayRenderer(InputKind.TEXTURE_2D, timer).apply { setSettings(MeterReferenceSettings.all) }
        val settings = ScopeSettings(histogram = true, waveform = true, vectorscope = true, waveformMode = WaveformMode.RGB_PARADE, updateEveryFrames = 1)
        val scopes = ScopeRenderer(InputKind.TEXTURE_2D, settings, previewWidth, previewHeight, caps, timer)
        val fbo = GlTestSupport.framebuffer(previewWidth, previewHeight)
        repeat(FRAMES) { frame ->
            scopes.update(source, frameIndex = frame.toLong(), restoreFramebuffer = fbo[0])
            overlay.draw(source, previewWidth, previewHeight, previewWidth, previewHeight, frameIndex = frame.toLong())
            scopes.drawHistogram(40, 40, 512, 200)
            scopes.drawWaveform(600, 40, 512, 256)
            scopes.drawVectorscope(1200, 40, 256, 256)
            // A real preview would swap here; glFlush keeps the queue moving without stalling the CPU.
            GLES30.glFlush()
            timer.poll()
        }
        GLES30.glFinish()
        timer.poll()
        val gl = "${GLES30.glGetString(GLES30.GL_RENDERER)} ES ${caps.majorVersion}.${caps.minorVersion}"
        Log.i("UCSPIKE", "METER_COST gpu=\"$gl\" timer=${timer.mode} accumulation=${scopes.format} analysis=${scopes.analysisWidth}x${scopes.analysisHeight}")
        for (pass in MeterPass.entries) {
            val s = timer.summary(pass.ordinal) ?: continue
            Log.i("UCSPIKE", "METER_COST pass=$pass samples=${s.samples} meanMs=${s.meanMs} p95Ms=${s.p95Ms} maxMs=${s.maxMs}")
        }
        Log.i("UCSPIKE", "METER_COST skipped=${timer.skipped}")
        GlTestSupport.release(fbo)
        GLES30.glDeleteTextures(1, intArrayOf(source), 0)
        overlay.release()
        scopes.release()
        timer.release()
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    private companion object {
        const val FRAMES = 120
    }
}
