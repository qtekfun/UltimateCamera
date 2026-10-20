package com.qtekfun.ultimatecamera.core.net.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.webrtc.VideoFrame

class AspectSinkTest {
    private class Plain(private val w: Int, private val h: Int) : VideoFrame.Buffer {
        override fun getWidth() = w
        override fun getHeight() = h
        override fun toI420(): VideoFrame.I420Buffer? = null
        override fun retain() = Unit
        override fun release() = Unit
        override fun cropAndScale(cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int): VideoFrame.Buffer =
            Plain(scaleWidth, scaleHeight)
    }

    @Test
    fun anUprightCameraIsShownUprightNotSideways() {
        // Regression (D96): the camera sends sensor-framed 16:9 frames; held upright it marks them turned by 90°, and
        // the monitor must show 9:16 instead of the picture lying on its side in a 16:9 box.
        val sink = AspectSink()
        assertNull(sink.aspect.value)
        sink.onFrame(VideoFrame(Plain(1280, 720), 90, 0L))
        assertEquals(720f / 1280f, sink.aspect.value!!, 1e-6f)
        sink.onFrame(VideoFrame(Plain(1280, 720), 0, 0L))
        assertEquals(1280f / 720f, sink.aspect.value!!, 1e-6f)
        sink.onFrame(VideoFrame(Plain(1280, 720), 270, 0L))
        assertEquals(720f / 1280f, sink.aspect.value!!, 1e-6f)
    }

    @Test
    fun anEmptyFrameHasNoShape() {
        assertNull(AspectSink.shownAspect(0, 720))
    }
}
