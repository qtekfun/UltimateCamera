package com.qtekfun.ultimatecamera.core.gpu.meters

/** Which scope the live preview shows (decision D46: one at a time, in a small corner panel). */
enum class ScopeKind { NONE, HISTOGRAM, WAVEFORM, VECTORSCOPE }

/**
 * Everything the camera preview draws on top of the monitored image (REQ-MON-001): the overlay pass and at most one
 * scope. Never applied to the recording. Immutable; the camera screen hands a new instance to the renderer when the
 * user changes something (`CameraRenderer.setMonitorSettings`), never per frame.
 */
data class MonitorSettings(
    val overlay: MonitorOverlaySettings = MonitorOverlaySettings(),
    val scope: ScopeKind = ScopeKind.NONE,
    /** Scope analysis every n-th preview frame; raised on modest or hot phones (D68). */
    val scopeUpdateEvery: Int = SCOPE_UPDATE_EVERY
) {
    init {
        require(scopeUpdateEvery > 0) { "scopeUpdateEvery must be positive" }
    }

    /** True when the overlay pass changes anything: without it (and without a scope) the preview is drawn directly. */
    val overlayActive: Boolean
        get() {
            val o = overlay
            val g = o.guides
            val guides = g.thirds || g.centre || g.safeAreas.isNotEmpty() || g.frameLineAspect != null
            return o.peaking.enabled || o.zebras.primary.enabled || o.zebras.secondary.enabled || o.falseColor.enabled || guides ||
                o.desqueeze != Desqueeze.NONE
        }

    val active: Boolean get() = overlayActive || scope != ScopeKind.NONE

    /**
     * Whether preview frame [frameIndex] must go through the screen-sized offscreen target: always with an overlay, and
     * with only a scope just on its analysis frames (the others draw the plain preview plus the last scope result, D68).
     */
    fun needsOffscreenPass(frameIndex: Long): Boolean = overlayActive || (scope != ScopeKind.NONE && frameIndex % scopeUpdateEvery == 0L)

    /**
     * Scope analysis settings for the renderer. Analysis runs every [SCOPE_UPDATE_EVERY] frames (~15 Hz at 60 fps) on
     * a 1/8 copy of the preview, and only for the scope shown: on Pixel 8 the vectorscope scatter alone costs up to
     * 14.8 ms p95, too much to pay every frame of a 4K60 take (decision D46).
     */
    fun scopeSettings(): ScopeSettings = ScopeSettings(
        histogram = scope == ScopeKind.HISTOGRAM,
        waveform = scope == ScopeKind.WAVEFORM,
        vectorscope = scope == ScopeKind.VECTORSCOPE,
        analysisDivisor = SCOPE_ANALYSIS_DIVISOR,
        updateEveryFrames = scopeUpdateEvery
    )

    companion object {
        val OFF = MonitorSettings()

        /** Scope analysis rate: every 4th camera frame (15 Hz at 60 fps, 7.5 Hz at 30 fps). */
        const val SCOPE_UPDATE_EVERY = 4
        const val SCOPE_ANALYSIS_DIVISOR = 8
    }
}

/** Where the scope panel goes on the preview surface (GL window coordinates, bottom-left origin). */
object ScopePanelLayout {
    /** Panel width as a fraction of the surface width; the height is capped at [MAX_HEIGHT_FRACTION] of the surface. */
    const val WIDTH_FRACTION = 0.28f

    /** In portrait (surface taller than wide) the panel is wider and sits above the bottom controls. */
    const val PORTRAIT_WIDTH_FRACTION = 0.45f
    const val PORTRAIT_BOTTOM_FRACTION = 0.22f
    const val MAX_HEIGHT_FRACTION = 0.4f

    /** Margin from the content's left edge, and from its bottom (above the audio meter), as fractions of the surface. */
    const val MARGIN_X_FRACTION = 0.02f
    const val MARGIN_BOTTOM_FRACTION = 0.14f

    /** Width / height of the waveform and histogram panels; the vectorscope is square. */
    const val WIDE_ASPECT = 1.6f

    /**
     * The panel for [kind] inside [content] (the image area within the surface, already turned to the display),
     * anchored bottom-left: above the audio meter in landscape, above the bottom controls in portrait. Null for
     * [ScopeKind.NONE] or a surface too small to show it.
     */
    fun rect(kind: ScopeKind, surfaceWidth: Int, surfaceHeight: Int, content: PixelRect): PixelRect? {
        if (kind == ScopeKind.NONE || surfaceWidth <= 0 || surfaceHeight <= 0) return null
        val aspect = if (kind == ScopeKind.VECTORSCOPE) 1f else WIDE_ASPECT
        val portrait = surfaceHeight > surfaceWidth
        var w = (surfaceWidth * if (portrait) PORTRAIT_WIDTH_FRACTION else WIDTH_FRACTION).toInt()
        var h = (w / aspect).toInt()
        val maxH = (surfaceHeight * MAX_HEIGHT_FRACTION).toInt()
        if (h > maxH) {
            h = maxH
            w = (h * aspect).toInt()
        }
        val x = content.x + (surfaceWidth * MARGIN_X_FRACTION).toInt()
        val y = if (portrait) {
            maxOf(content.y, (surfaceHeight * PORTRAIT_BOTTOM_FRACTION).toInt())
        } else {
            content.y + (surfaceHeight * MARGIN_BOTTOM_FRACTION).toInt()
        }
        val fits = x + w <= surfaceWidth && y + h <= surfaceHeight
        return if (w < MIN_SIDE || h < MIN_SIDE || !fits) null else PixelRect(x, y, w, h)
    }

    private const val MIN_SIDE = 32
}
