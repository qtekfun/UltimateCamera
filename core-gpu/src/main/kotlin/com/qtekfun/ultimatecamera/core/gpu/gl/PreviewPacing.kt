package com.qtekfun.ultimatecamera.core.gpu.gl

/** Which camera frames reach the preview when its rate is lowered (REQ-NFR-001, D68); the encoder gets them all. */
object PreviewPacing {
    /**
     * The screen never shows more than this (D97): a 60 fps mode is previewed at 30, which halves the preview's GPU
     * work and lets a 120 Hz display drop its refresh rate; the take still gets every frame.
     */
    const val MAX_FPS = 30

    /** True when frame number [frame] (0-based) is drawn on the preview at one frame in [everyNth] (≤ 1: every frame). */
    fun draws(frame: Int, everyNth: Int): Boolean = everyNth <= 1 || frame % everyNth == 0

    /**
     * The preview's one-in-n for a camera at [cameraFps]: thinned to at most [MAX_FPS], then by [thermalEveryNth] (the
     * phone's load, D68: 2 halves the shown rate).
     */
    fun everyNth(cameraFps: Int, thermalEveryNth: Int): Int = StreamOutput.everyNth(cameraFps, MAX_FPS) * thermalEveryNth.coerceAtLeast(1)

    /** The rate the preview shows at, as the frame-rate hint of its surface. */
    fun shownFps(cameraFps: Int, everyNth: Int): Float = cameraFps.toFloat() / everyNth.coerceAtLeast(1)
}
