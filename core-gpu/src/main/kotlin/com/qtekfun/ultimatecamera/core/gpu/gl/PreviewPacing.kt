package com.qtekfun.ultimatecamera.core.gpu.gl

/** Which camera frames reach the preview when its rate is lowered (REQ-NFR-001, D68); the encoder gets them all. */
object PreviewPacing {
    /** True when frame number [frame] (0-based) is drawn on the preview at one frame in [everyNth] (≤ 1: every frame). */
    fun draws(frame: Int, everyNth: Int): Boolean = everyNth <= 1 || frame % everyNth == 0
}
