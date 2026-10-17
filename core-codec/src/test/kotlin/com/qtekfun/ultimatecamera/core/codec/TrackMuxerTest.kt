package com.qtekfun.ultimatecamera.core.codec

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMuxerTest {
    private data class Sample(val track: Int, val bytes: List<Byte>, val ptsUs: Long, val flags: Int)

    private class FakeSink(private val failWrites: Boolean = false, private val failStop: Boolean = false) : MuxerSink<String> {
        val tracks = mutableListOf<String>()
        val samples = mutableListOf<Sample>()
        val calls = mutableListOf<String>()

        override fun addTrack(format: String): Int {
            calls += "add:$format"
            tracks += format
            return tracks.size - 1
        }

        override fun start() {
            calls += "start"
        }

        override fun writeSample(track: Int, buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            if (failWrites) error("refused")
            val bytes = (offset until offset + size).map { buffer.get(it) }
            samples += Sample(track, bytes, presentationTimeUs, flags)
        }

        override fun stop() {
            calls += "stop"
            if (failStop) error("no samples")
        }

        override fun release() {
            calls += "release"
        }
    }

    private fun buffer(vararg bytes: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun startsOnceEveryTrackIsAddedAndFlushesEarlySamplesInOrder() {
        val sink = FakeSink()
        val muxer = TrackMuxer(sink, expectedTracks = 2)
        val video = muxer.addTrack("video")
        assertFalse(muxer.isStarted)
        // The key frame and the next frame arrive before audio is ready; the encoder reuses its buffer afterwards.
        val reused = buffer(9, 1, 2, 3, 9)
        muxer.writeSample(video, reused, offset = 1, size = 3, presentationTimeUs = 0, flags = 1)
        reused.clear()
        reused.put(0, 7)
        reused.put(1, 7)
        muxer.writeSample(video, reused, offset = 0, size = 2, presentationTimeUs = 16_666, flags = 0)
        assertTrue("nothing reaches the muxer before start", sink.samples.isEmpty())
        val audio = muxer.addTrack("audio")
        assertTrue(muxer.isStarted)
        assertEquals(listOf("add:video", "add:audio", "start"), sink.calls)
        assertEquals(
            listOf(Sample(video, listOf<Byte>(1, 2, 3), 0, 1), Sample(video, listOf<Byte>(7, 7), 16_666, 0)),
            sink.samples
        )
        muxer.writeSample(audio, buffer(5, 6), 0, 2, 21_333, 0)
        assertEquals(Sample(audio, listOf<Byte>(5, 6), 21_333, 0), sink.samples.last())
        assertNull(muxer.error)
    }

    @Test
    fun noTrackCanBeAddedOnceStartedOrStopped() {
        val muxer = TrackMuxer(FakeSink(), expectedTracks = 1)
        muxer.addTrack("video")
        assertThrows(IllegalStateException::class.java) { muxer.addTrack("late") }
        val stopped = TrackMuxer(FakeSink(), expectedTracks = 2)
        stopped.stop()
        assertThrows(IllegalStateException::class.java) { stopped.addTrack("video") }
    }

    @Test
    fun aTrackThatNeverAnnouncesItsFormatOverflowsThePendingQueue() {
        val sink = FakeSink()
        val muxer = TrackMuxer(sink, expectedTracks = 2, maxPendingBytes = 10)
        val video = muxer.addTrack("video")
        muxer.writeSample(video, buffer(1, 2, 3, 4, 5, 6), 0, 6, 0, 0)
        muxer.writeSample(video, buffer(1, 2, 3, 4), 0, 4, 1, 0)
        assertNull("exactly at the limit is fine", muxer.error)
        muxer.writeSample(video, buffer(1), 0, 1, 2, 0)
        assertEquals("muxer did not start: a track never announced its format", muxer.error)
        // The queued samples are still written in order when the track finally comes.
        muxer.addTrack("audio")
        assertEquals(listOf(0L, 1L), sink.samples.map { it.ptsUs })
    }

    @Test
    fun theFirstWriteErrorIsKept() {
        val muxer = TrackMuxer(FakeSink(failWrites = true), expectedTracks = 1)
        val t = muxer.addTrack("video")
        muxer.writeSample(t, buffer(1), 0, 1, 0, 0)
        assertEquals("writeSampleData: refused", muxer.error)
        muxer.stop()
        assertEquals("writeSampleData: refused", muxer.error)
    }

    @Test
    fun stopFinishesOnceAndIgnoresLaterSamples() {
        val sink = FakeSink()
        val muxer = TrackMuxer(sink, expectedTracks = 1)
        val t = muxer.addTrack("video")
        muxer.stop()
        muxer.stop()
        muxer.writeSample(t, buffer(1), 0, 1, 0, 0)
        assertEquals(listOf("add:video", "start", "stop", "release"), sink.calls)
        assertTrue(sink.samples.isEmpty())
    }

    @Test
    fun aMuxerThatNeverStartedIsOnlyReleased() {
        val sink = FakeSink()
        val muxer = TrackMuxer(sink, expectedTracks = 2)
        muxer.addTrack("video")
        muxer.stop()
        // MediaMuxer.stop() throws on a muxer that never started: it must not be called.
        assertEquals(listOf("add:video", "release"), sink.calls)
        assertNull(muxer.error)
        assertFalse(muxer.isStarted)
    }

    @Test
    fun aFailingStopIsReported() {
        val muxer = TrackMuxer(FakeSink(failStop = true), expectedTracks = 1)
        muxer.addTrack("video")
        muxer.stop()
        assertEquals("muxer.stop: no samples", muxer.error)
    }

    @Test
    fun encodersOnTwoThreadsDoNotLoseSamples() {
        val sink = FakeSink()
        val muxer = TrackMuxer(sink, expectedTracks = 2)
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val perTrack = 500
        listOf("video", "audio").forEach { name ->
            pool.execute {
                val track = muxer.addTrack(name)
                ready.countDown()
                repeat(perTrack) { muxer.writeSample(track, buffer(it and 0x7f), 0, 1, it.toLong(), 0) }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        muxer.stop()
        assertEquals(2 * perTrack, sink.samples.size)
        sink.samples.groupBy { it.track }.values.forEach { samples ->
            assertEquals("each track stays in order", (0 until perTrack).map { it.toLong() }, samples.map { it.ptsUs })
        }
    }
}
