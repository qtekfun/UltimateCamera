package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Regression for the HDR bracket crash: a burst larger than the reader's buffer count must not throw. */
class FrameDrainTest {
    /** Behaves like ImageReader: at most [maxImages] acquired frames open at once, extra ones wait in the queue. */
    private class FakeReader(private val maxImages: Int) {
        val queued = ArrayDeque<Int>()
        var held = 0
        var mostHeld = 0
        val closed = mutableListOf<Int>()

        fun acquireNextImage(): Int? {
            if (queued.isEmpty()) return null
            check(held < maxImages) { "maxImages ($maxImages) has already been acquired, call #close before acquiring more." }
            held++
            mostHeld = maxOf(mostHeld, held)
            return queued.removeFirst()
        }

        fun close() {
            held--
        }

        fun close(frame: Int) {
            closed += frame
            close()
        }
    }

    @Test
    fun videoDepthTakesTheNewestFrameAndClosesTheOlderOnes() {
        // D92: three depth frames queued while the camera thread was busy. One acquire per callback gave frame 1 and
        // left 2 and 3 for later callbacks, so the depth trailed the picture; the newest is the one to use.
        val reader = FakeReader(maxImages = 2)
        reader.queued.addAll(listOf(1, 2, 3, 4, 5))
        assertEquals(5, FrameDrain.newest(reader::acquireNextImage, reader::close))
        assertEquals(listOf(1, 2, 3, 4), reader.closed)
        assertEquals(2, reader.mostHeld)
        assertEquals(1, reader.held)
        reader.close(5)
        assertNull(FrameDrain.newest(reader::acquireNextImage, reader::close))
        assertEquals(0, reader.held)
    }

    @Test
    fun aBurstBiggerThanTheReaderIsDeliveredWithoutThrowing() {
        val reader = FakeReader(maxImages = 2)
        val delivered = mutableListOf<Int>()
        reader.queued.addAll(listOf(1, 2, 3))

        assertEquals(2, FrameDrain.drain(reader::acquireNextImage) { delivered += it })
        assertEquals(listOf(1, 2), delivered)

        // The consumer writes frame 1 to disk and closes it; the waiting frame 3 is then picked up.
        reader.close()
        assertEquals(1, FrameDrain.drain(reader::acquireNextImage) { delivered += it })
        assertEquals(listOf(1, 2, 3), delivered)
    }

    @Test
    fun nothingReadyTakesNothing() {
        assertEquals(0, FrameDrain.drain(FakeReader(2)::acquireNextImage) { error("no frame expected") })
    }
}
