package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.LogSource
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.ProxyFrameGate
import java.util.concurrent.atomic.AtomicInteger

/**
 * The proxy encoder's input on the render thread (D59). The proxy is H.264 8-bit, and an encoder fed RGBA1010102
 * buffers by a 10-bit EGL config may produce nothing at all (Pixel 8, `c2.exynos.h264.encoder`: pixel format 0x2b, no
 * output until EOS). So the proxy surface always comes from an RGBA8888 config: the main context's when it is 8-bit,
 * otherwise a second recordable RGBA8888 context sharing the main one (the camera's external texture is shared), with
 * its own [LutRenderer] (VAOs are not shared) and its own copy of the look.
 *
 * When the proxy encoder takes frames but produces no output for [ProxyFrameGate.stalled], feeding stops and the reason
 * is logged once and kept in [problem]; the take itself goes on. Render thread only.
 */
internal class ProxyStage(private val main: EglCore, private val mainIdle: EGLSurface, private val interpolation: LutInterpolation) {
    private var core: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var renderer: LutRenderer? = null
    private var rendererLog = false
    private var target: Target? = null

    // Look requested by the camera renderer; uploaded into the 8-bit context at the next proxy frame.
    private var log = false
    private var recordLut: CubeLut? = null
    private var monitorLut: CubeLut? = null
    private var lookDirty = true

    /** Why the proxy stopped receiving frames, or null. */
    @Volatile var problem: String? = null
        private set

    /** True when the proxy draws with its own 8-bit context (the main one is 10-bit). */
    private val separate: Boolean get() = main.depth != EglColorDepth.RGBA8888

    fun setLook(log: Boolean, recordLut: CubeLut?, monitorLut: CubeLut?) {
        this.log = log
        this.recordLut = recordLut
        this.monitorLut = monitorLut
        lookDirty = true
    }

    fun start(surface: Surface, width: Int, height: Int, fromNanos: Long, gate: ProxyFrameGate, encoded: AtomicInteger, monitorLook: Boolean) {
        stop()
        problem = null
        val egl = if (separate) separateCore() else main
        target = Target(egl.createWindowSurface(surface, false), egl, width, height, fromNanos, gate, encoded, monitorLook)
    }

    /**
     * Draws the current camera frame ([texture], [texMatrix]) into the proxy when its gate lets it through. [mainRenderer]
     * draws it when the proxy shares the main (8-bit) context.
     */
    fun draw(texture: Int, texMatrix: FloatArray, timestamp: Long, mainRenderer: (monitorLook: Boolean) -> LutRenderer) {
        val t = target ?: return
        if (timestamp < t.fromNanos || !t.gate.shouldDraw(t.encoded.get())) {
            if (t.gate.stalled) stall(t)
            return
        }
        t.egl.makeCurrent(t.surface)
        val r = if (separate) ownRenderer(t.monitorLook) else mainRenderer(t.monitorLook)
        r.draw(texture, t.width, t.height, texMatrix)
        t.egl.setPresentationTime(t.surface, timestamp)
        t.egl.swapBuffers(t.surface)
    }

    private fun stall(t: Target) {
        problem = "proxy encoder produced no output for ${t.gate.submitted} frames"
        Log.w(TAG, "$problem: stopped feeding it")
        stop()
    }

    fun stop() {
        val t = target ?: return
        target = null
        t.egl.makeCurrent(if (t.egl === main) mainIdle else pbuffer ?: mainIdle)
        t.egl.releaseSurface(t.surface)
    }

    /** Frees the 8-bit context (if any). The caller makes its own context current afterwards. */
    fun release() {
        stop()
        core?.let { c ->
            pbuffer?.let { p ->
                c.makeCurrent(p)
                renderer?.release()
                c.releaseSurface(p)
            }
            c.release()
        }
        core = null
        pbuffer = null
        renderer = null
    }

    private fun separateCore(): EglCore = core ?: EglCore(EglColorDepth.RGBA8888, recordable = true, shareWith = main).also { c ->
        core = c
        pbuffer = c.createPbufferSurface(1, 1)
        lookDirty = true
    }

    /** The 8-bit context's renderer with the look applied; recreated only when the Log stage changes. */
    private fun ownRenderer(monitorLook: Boolean): LutRenderer {
        if (renderer == null || rendererLog != log) {
            renderer?.release()
            renderer = LutRenderer(InputKind.EXTERNAL_OES, interpolation, if (log) LogSource.HLG else null)
            rendererLog = log
            lookDirty = true
        }
        val r = checkNotNull(renderer)
        if (lookDirty) {
            r.setLut(if (monitorLook) monitorLut else recordLut)
            lookDirty = false
        }
        return r
    }

    private class Target(
        val surface: EGLSurface,
        val egl: EglCore,
        val width: Int,
        val height: Int,
        val fromNanos: Long,
        val gate: ProxyFrameGate,
        val encoded: AtomicInteger,
        val monitorLook: Boolean
    )

    private companion object {
        const val TAG = "UcProxy"
    }
}
