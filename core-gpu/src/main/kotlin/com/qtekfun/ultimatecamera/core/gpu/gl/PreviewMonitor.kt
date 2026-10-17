package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorGeometry
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeKind
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopePanelLayout

/**
 * The monitoring tools of the live preview (REQ-MON-001, REQ-MON-002, decisions D34 and D46): overlays (zebras, false
 * colour, peaking, guides, desqueeze) and one scope, drawn into the **preview surface only**. [CameraRenderer] uses it
 * only while [active]; the encoder path never goes through it, so nothing is burned into a clip.
 *
 * Per preview frame while active:
 * 1. the caller draws the monitored image (camera → LUT / monitor LUT) into [beginFrame]'s offscreen target, sized as
 *    the preview's image area (not the video size: a 4K take still costs only a screen-sized pass here);
 * 2. [finishFrame] runs the scope analysis on that target every [MonitorSettings.SCOPE_UPDATE_EVERY] frames (only for
 *    the scope shown), draws the target with the overlays into the preview surface (the overlay pass is a passthrough
 *    when no overlay is on) and the last scope result in a corner panel.
 * Renderers and targets are created lazily on the GL thread when first needed and kept while sizes do not change; per
 * frame nothing is allocated.
 *
 * GL thread only (create, [apply], frames, [release]); the context must be current.
 */
internal class PreviewMonitor(private val timer: GpuTimer? = null) {
    private var settings = MonitorSettings.OFF
    private var target: RenderTarget? = null
    private var overlay: MonitorOverlayRenderer? = null
    private var scopes: ScopeRenderer? = null
    private var scopesConfigured = false
    private var frame = 0L

    val active: Boolean get() = settings.active

    /**
     * This frame needs the offscreen pass ([beginFrame] / [finishFrame]): an overlay is on, or the scope analyses this
     * frame. Otherwise the caller draws the plain preview and then [drawScopeOnly] (D68).
     */
    val needsOffscreen: Boolean get() = settings.needsOffscreenPass(frame)

    /**
     * Only a scope is on and this is not one of its analysis frames: the caller drew the plain image ([imageWidth]×
     * [imageHeight], letterboxed) into the preview surface; this adds the last scope result in its panel.
     */
    fun drawScopeOnly(imageWidth: Int, imageHeight: Int, surfaceWidth: Int, surfaceHeight: Int) {
        val s = settings
        if (s.scope != ScopeKind.NONE) drawScope(s.scope, imageWidth, imageHeight, surfaceWidth, surfaceHeight)
        frame++
    }

    fun apply(newSettings: MonitorSettings) {
        settings = newSettings
        if (newSettings.active) {
            (overlay ?: MonitorOverlayRenderer(InputKind.TEXTURE_2D, timer).also { overlay = it }).setSettings(newSettings.overlay)
        }
        // The scope renderer is reconfigured on the next frame, when the source size is known.
        scopesConfigured = false
    }

    /** Binds an offscreen target of [width]×[height] (created or resized as needed) to draw the monitored image into. */
    fun beginFrame(width: Int, height: Int) {
        val t = target?.takeIf { it.width == width && it.height == height } ?: createTarget(width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.framebuffer)
    }

    /**
     * Draws the frame begun with [beginFrame] into the default framebuffer (the preview surface, [surfaceWidth]×
     * [surfaceHeight]), letterboxed to the image's aspect, with the overlays and the scope panel.
     */
    fun finishFrame(surfaceWidth: Int, surfaceHeight: Int) {
        val t = target ?: return
        val s = settings
        if (s.scope != ScopeKind.NONE) {
            val sc = scopeRenderer(t)
            sc.update(t.texture, frameIndex = frame, restoreFramebuffer = 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        overlay?.draw(t.texture, t.width, t.height, surfaceWidth, surfaceHeight, frameIndex = frame)
        if (s.scope != ScopeKind.NONE) drawScope(s.scope, t.width, t.height, surfaceWidth, surfaceHeight)
        frame++
    }

    private fun drawScope(kind: ScopeKind, imageWidth: Int, imageHeight: Int, surfaceWidth: Int, surfaceHeight: Int) {
        val sc = scopes ?: return
        val content = MonitorGeometry.contentRect(surfaceWidth, surfaceHeight, imageWidth, imageHeight, settings.overlay.desqueeze)
        val r = ScopePanelLayout.rect(kind, surfaceWidth, surfaceHeight, content) ?: return
        when (kind) {
            ScopeKind.HISTOGRAM -> sc.drawHistogram(r.x, r.y, r.width, r.height)
            ScopeKind.WAVEFORM -> sc.drawWaveform(r.x, r.y, r.width, r.height)
            ScopeKind.VECTORSCOPE -> sc.drawVectorscope(r.x, r.y, r.width, r.height)
            ScopeKind.NONE -> Unit
        }
    }

    private fun scopeRenderer(t: RenderTarget): ScopeRenderer {
        val existing = scopes
        if (existing != null) {
            if (!scopesConfigured) existing.configure(settings.scopeSettings(), t.width, t.height)
            scopesConfigured = true
            return existing
        }
        scopesConfigured = true
        return ScopeRenderer(InputKind.TEXTURE_2D, settings.scopeSettings(), t.width, t.height, timer = timer).also { scopes = it }
    }

    private fun createTarget(width: Int, height: Int): RenderTarget {
        target?.release()
        val t = RenderTarget(width, height, GLES30.GL_RGBA8)
        // Linear filtering: the overlay pass may stretch the image (desqueeze) or scale it to the surface.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        target = t
        // A new source size changes the scope analysis size.
        scopesConfigured = false
        return t
    }

    fun release() {
        target?.release()
        target = null
        overlay?.release()
        overlay = null
        scopes?.release()
        scopes = null
    }
}
