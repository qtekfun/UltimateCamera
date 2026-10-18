package com.qtekfun.ultimatecamera.core.camera

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the portrait crash on the Pixel 8 (D91): with live depth on a stereo pair, depth frames kept arriving
 * while a shot waited for its JPEG and read the outputs one after the other; queued without bound and acquired one per
 * callback, they used up the reader's two buffers and `acquireNextImage` threw on the camera thread.
 */
class PortraitFramesTest {
    /** Like ImageReader: at most [maxImages] acquired frames open at once, extra ones wait in the reader. */
    private class FakeReader(private val maxImages: Int) {
        val waiting = ArrayDeque<Frame>()
        var held = 0
        var mostHeld = 0

        inner class Frame(val n: Int) {
            var closed = false

            fun close() {
                if (closed) return
                closed = true
                held--
            }
        }

        fun arrive(n: Int) = waiting.addLast(Frame(n))

        fun acquireNextImage(): Frame? {
            if (waiting.isEmpty()) return null
            check(held < maxImages) { "maxImages ($maxImages) has already been acquired, call #close before acquiring more." }
            held++
            mostHeld = maxOf(mostHeld, held)
            return waiting.removeFirst()
        }
    }

    private fun aux(): Pair<FakeReader, PortraitFrames<FakeReader.Frame>> {
        val reader = FakeReader(DepthCaptureStream.AUX_MAX_IMAGES)
        return reader to PortraitFrames(DepthCaptureStream.AUX_KEEP) { f -> f.close() }
    }

    @Test
    fun depthFramesArrivingDuringAShotNeverExhaustTheReader() = runBlocking {
        val (reader, frames) = aux()
        // The shot holds a candidate frame (the first stereo output's, or an in-flight preview frame) ...
        reader.arrive(0)
        frames.onAvailable(reader::acquireNextImage, queue = true)
        val candidate = frames.receive()
        // ... while the preview's in-flight depth frames and finally the still's keep arriving, one callback each.
        for (n in 1..8) {
            reader.arrive(n)
            frames.onAvailable(reader::acquireNextImage, queue = true)
        }
        assertTrue("held ${reader.mostHeld}", reader.mostHeld <= DepthCaptureStream.AUX_MAX_IMAGES)
        assertEquals("candidate plus the newest queued", 1 + DepthCaptureStream.AUX_KEEP, reader.held)
        candidate.close()
        // The still (the last request that fills the outputs) is never the one dropped.
        val queued = List(DepthCaptureStream.AUX_KEEP) { frames.receive().also { it.close() }.n }
        assertEquals(listOf(7, 8), queued)
        assertEquals(0, reader.held)
    }

    @Test
    fun framesLeftByAShotAreClosedWhenLiveDepthResumes() {
        val (reader, frames) = aux()
        repeat(DepthCaptureStream.AUX_KEEP) { n ->
            reader.arrive(n)
            frames.onAvailable(reader::acquireNextImage, queue = true)
        }
        assertEquals(DepthCaptureStream.AUX_KEEP, reader.held)
        val live = mutableListOf<Int>()
        for (n in 10..20) {
            reader.arrive(n)
            frames.onAvailable(reader::acquireNextImage, queue = false) { live += it.n }
        }
        assertEquals((10..20).toList(), live)
        assertEquals("live frames and the shot's leftovers all closed", 0, reader.held)
    }

    @Test
    fun aFrameThatFoundEveryBufferHeldIsTakenOnTheNextCall() = runBlocking {
        val (reader, frames) = aux()
        val held = mutableListOf<FakeReader.Frame>()
        repeat(DepthCaptureStream.AUX_MAX_IMAGES) { n ->
            reader.arrive(n)
            frames.onAvailable(reader::acquireNextImage, queue = true)
            held += frames.receive()
        }
        reader.arrive(99)
        assertEquals(0, frames.onAvailable(reader::acquireNextImage, queue = true))
        held.removeAt(0).close()
        assertEquals(1, frames.onAvailable(reader::acquireNextImage, queue = true))
        assertEquals(99, frames.receive().n)
    }

    @Test
    fun discardAndShutdownCloseEverything() = runBlocking {
        val (reader, frames) = aux()
        repeat(2) { n ->
            reader.arrive(n)
            frames.onAvailable(reader::acquireNextImage, queue = true)
        }
        frames.discard()
        assertEquals(0, reader.held)
        assertNull(withTimeoutOrNull(50) { frames.receive() })
        reader.arrive(5)
        frames.onAvailable(reader::acquireNextImage, queue = true)
        frames.shutdown()
        reader.arrive(6)
        frames.onAvailable(reader::acquireNextImage, queue = true)
        assertEquals(0, reader.held)
    }
}
