package com.qtekfun.ultimatecamera.core.net.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * The received picture's shape as shown (width over height after the frame's rotation, D96): 16:9 for a camera held
 * in landscape, 9:16 for one held upright. Changes only when the shape does; allocation-free per frame.
 */
internal class AspectSink : VideoSink {
    private val _aspect = MutableStateFlow<Float?>(null)
    val aspect: StateFlow<Float?> = _aspect
    private var width = 0
    private var height = 0

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w == width && h == height) return
        width = w
        height = h
        _aspect.value = shownAspect(w, h)
    }

    companion object {
        /** [width] over [height], null for an empty frame. */
        fun shownAspect(width: Int, height: Int): Float? = if (width > 0 && height > 0) width.toFloat() / height else null
    }
}
