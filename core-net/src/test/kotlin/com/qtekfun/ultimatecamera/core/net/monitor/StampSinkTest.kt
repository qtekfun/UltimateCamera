package com.qtekfun.ultimatecamera.core.net.monitor

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.VideoFrame

class StampSinkTest {
    /** A frame whose luma at (x, y) is (x + 3 * y) mod 256, so a row identifies itself. */
    private class Plane(private val w: Int, private val h: Int, val i420: Boolean = true, val failCrop: Boolean = false) : VideoFrame.Buffer {
        val buffers = mutableListOf<Band>()

        override fun getWidth() = w

        override fun getHeight() = h

        override fun toI420(): VideoFrame.I420Buffer? = null

        override fun retain() = Unit

        override fun release() = Unit

        override fun cropAndScale(cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int): VideoFrame.Buffer {
            check(!failCrop) { "crop failed" }
            assertEquals(StampStrip.BAND_ROWS, cropHeight)
            assertEquals(scaleHeight, cropHeight)
            assertEquals(w, scaleWidth)
            return Band(w, cropY, i420).also { buffers += it }
        }
    }

    /** The two-row band; padded stride and a non-zero start position, as real buffers have. */
    private class Band(private val width: Int, val top: Int, private val convertible: Boolean) : VideoFrame.I420Buffer {
        var released = 0
        val children = mutableListOf<Band>()
        private val stride = width + 16
        private val data: ByteBuffer = ByteBuffer.allocate(8 + stride * StampStrip.BAND_ROWS).apply {
            for (row in 0 until StampStrip.BAND_ROWS) {
                for (x in 0 until width) put(8 + row * stride + x, ((x + 3 * (top + row)) % 256).toByte())
            }
            position(8)
        }

        override fun getWidth() = width

        override fun getHeight() = StampStrip.BAND_ROWS

        override fun toI420(): VideoFrame.I420Buffer? = if (convertible) Band(width, top, true).also { children += it } else null

        override fun retain() = Unit

        override fun release() {
            released++
        }

        override fun cropAndScale(cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int, scaleWidth: Int, scaleHeight: Int) = error("not used")

        override fun getDataY(): ByteBuffer = data.duplicate().also { it.position(8) }

        override fun getDataU(): ByteBuffer = ByteBuffer.allocate(0)

        override fun getDataV(): ByteBuffer = ByteBuffer.allocate(0)

        override fun getStrideY() = stride

        override fun getStrideU() = 0

        override fun getStrideV() = 0
    }

    private fun frame(buffer: VideoFrame.Buffer) = VideoFrame(buffer, 0, 0L)

    @Test
    fun theStampRowReachesTheDecoderAndEveryBufferIsReleased() {
        val rows = mutableListOf<IntArray>()
        val counters = mutableListOf<Int>()
        val decoder = StampDecoder { luma, width ->
            rows += luma.copyOf(width)
            42
        }
        val sink = StampSink(decoder, counters::add)
        val plane = Plane(64, 720)
        sink.onFrame(frame(plane))

        val row = StampStrip.row(720)
        assertArrayEquals(IntArray(64) { (it + 3 * row) % 256 }, rows.single())
        assertEquals(listOf(42), counters)
        assertEquals(1, sink.framesReceived)
        assertEquals(1, sink.stampsDecoded)
        assertEquals(0, sink.readFailures)
        val band = plane.buffers.single()
        assertEquals(StampStrip.bandTop(720), band.top)
        assertEquals(1, band.released)
        assertEquals(1, band.children.single().released)
    }

    @Test
    fun anUnreadableStampIsNotAFailure() {
        val sink = StampSink({ _, _ -> null }) { error("no counter expected") }
        sink.onFrame(frame(Plane(32, 1080)))
        assertEquals(1, sink.framesReceived)
        assertEquals(0, sink.stampsDecoded)
        assertEquals(0, sink.readFailures)
    }

    @Test
    fun withoutADecoderFramesAreOnlyCounted() {
        val plane = Plane(32, 1080)
        val sink = StampSink(null) { error("no counter expected") }
        sink.onFrame(frame(plane))
        assertEquals(1, sink.framesReceived)
        assertTrue(plane.buffers.isEmpty())
    }

    @Test
    fun tinyFailedOrUnconvertibleFramesAreCountedNeverThrown() {
        val sink = StampSink({ _, _ -> 1 }) { error("no counter expected") }
        sink.onFrame(frame(Plane(0, 720)))
        sink.onFrame(frame(Plane(32, 1)))
        sink.onFrame(frame(Plane(32, 720, failCrop = true)))
        val unconvertible = Plane(32, 720, i420 = false)
        sink.onFrame(frame(unconvertible))
        assertEquals(4, sink.framesReceived)
        assertEquals(4, sink.readFailures)
        assertEquals(0, sink.stampsDecoded)
        assertEquals("the band is released even when it cannot be converted", 1, unconvertible.buffers.single().released)
    }

    @Test
    fun theLumaRowGrowsForWiderFrames() {
        val widths = mutableListOf<Int>()
        val decoder = StampDecoder { luma, width ->
            widths += width
            assertTrue(luma.size >= width)
            7
        }
        val sink = StampSink(decoder) {}
        sink.onFrame(frame(Plane(16, 720)))
        sink.onFrame(frame(Plane(128, 720)))
        assertEquals(listOf(16, 128), widths)
        assertEquals(2, sink.stampsDecoded)
    }
}
