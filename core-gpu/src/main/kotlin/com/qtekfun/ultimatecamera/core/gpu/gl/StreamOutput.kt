package com.qtekfun.ultimatecamera.core.gpu.gl

import android.view.Surface

/**
 * A reduced copy of the monitored image for a remote monitor (D56): drawn into [surface] at [width] x [height] on every
 * [everyNthFrame]-th camera frame, see [CameraRenderer.setStreamOutput].
 */
data class StreamOutput(val surface: Surface, val width: Int, val height: Int, val everyNthFrame: Int) {
    init {
        require(width > 0 && height > 0 && everyNthFrame >= 1) { "invalid stream output ${width}x$height / $everyNthFrame" }
    }

    companion object {
        /** Widest stream sent to a monitor. */
        const val MAX_WIDTH = 1280

        /** Highest rate sent to a monitor. */
        const val MAX_FPS = 30

        /** Size (even, ≤ [MAX_WIDTH] wide, same aspect) of the stream for a [videoWidth] x [videoHeight] recording. */
        fun sizeFor(videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
            val width = minOf(MAX_WIDTH, videoWidth) and 1.inv()
            val height = (width.toLong() * videoHeight / videoWidth).toInt() and 1.inv()
            return width to height.coerceAtLeast(2)
        }

        /** Every how many camera frames one is streamed, so the stream stays at or under [MAX_FPS]. */
        fun everyNth(cameraFps: Int): Int = ((cameraFps + MAX_FPS - 1) / MAX_FPS).coerceAtLeast(1)
    }
}
