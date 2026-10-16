package com.qtekfun.ultimatecamera.feature.spike.pipeline

import android.view.Surface
import com.qtekfun.ultimatecamera.core.gpu.FrameStamp

/**
 * A third, smaller output for the remote monitor (Phase 0 WebRTC spike): the same graded frame plus the [FrameStamp]
 * counter strip, drawn into [surface] (the WebRTC capturer's input) on every [everyNthFrame]-th camera frame so modest
 * phones do not pay for a full-rate proxy. [onSubmitted] runs on the GL thread right after each swap with the counter
 * and System.nanoTime(); it must be cheap (the latency tracker's ring buffer).
 */
internal class ProxyOutput(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val everyNthFrame: Int,
    val onSubmitted: (counter: Int, nanoTime: Long) -> Unit
) {
    init {
        require(width >= FrameStamp.MIN_WIDTH && height > 0 && everyNthFrame >= 1) { "invalid proxy ${width}x$height / $everyNthFrame" }
    }
}
