package com.qtekfun.ultimatecamera.feature.camera.remote

import com.qtekfun.ultimatecamera.core.gpu.gl.StreamOutput

/**
 * Size, rate and bitrate of the live video a camera sends to its monitor (REQ-NET-004, D81): a small H.264 preview from
 * the renderer's stream output, separate from the take (its own hardware encoder inside libwebrtc, frames dropped
 * rather than queued). Enough to frame, focus-check and see the action; cheap enough that the take keeps its frame rate.
 * A modest phone (low-RAM or < 6 GiB) sends 640 px at ≤ 15 fps; others 960 px at ≤ 30 fps. Pure, unit-tested.
 */
data class MonitorStreamSpec(val width: Int, val height: Int, val maxFps: Int, val bitrateBps: Int) {
    companion object {
        const val WIDTH = 960
        const val FPS = 30
        const val BITRATE = 1_500_000
        const val MODEST_WIDTH = 640
        const val MODEST_FPS = 15
        const val MODEST_BITRATE = 700_000

        fun of(videoWidth: Int, videoHeight: Int, modestPhone: Boolean): MonitorStreamSpec {
            val cap = if (modestPhone) MODEST_WIDTH else WIDTH
            val (w, h) = StreamOutput.sizeFor(videoWidth, videoHeight)
            val width = minOf(w, cap) and 1.inv()
            val height = ((width.toLong() * h / w).toInt() and 1.inv()).coerceAtLeast(2)
            return if (modestPhone) MonitorStreamSpec(width, height, MODEST_FPS, MODEST_BITRATE) else MonitorStreamSpec(width, height, FPS, BITRATE)
        }
    }
}
