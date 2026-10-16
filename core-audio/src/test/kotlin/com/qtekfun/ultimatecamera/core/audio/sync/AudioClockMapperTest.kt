package com.qtekfun.ultimatecamera.core.audio.sync

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioClockMapperTest {
    private val nominal = 48_000
    private val framePeriodNanos = 1e9 / nominal

    /**
     * A synthetic device: its audio clock runs at nominal·(1 + ppm·1e-6) against the video clock, frame 0 was captured
     * at [startNanos], and every timestamp read has uniform jitter of ±[jitterNanos].
     */
    private class Timeline(val rate: Double, val startNanos: Long, val jitterNanos: Long, seed: Long = 1) {
        private val random = Random(seed)
        fun trueNanos(frame: Long): Double = startNanos + frame * 1e9 / rate
        fun read(frame: Long): Long = (trueNanos(frame) + (random.nextDouble() * 2 - 1) * jitterNanos).toLong()
    }

    private fun feed(mapper: AudioClockMapper, timeline: Timeline, fromFrame: Long, count: Int, everyFrames: Long = 4_800) {
        for (k in 0 until count) {
            val frame = fromFrame + k * everyFrames
            mapper.add(frame, timeline.read(frame))
        }
    }

    @Test
    fun exactTimestampsMapSampleAccurately() {
        val timeline = Timeline(nominal.toDouble(), startNanos = 5_000_000_000L, jitterNanos = 0)
        val mapper = AudioClockMapper(nominal)
        feed(mapper, timeline, 0, 20)
        for (frame in longArrayOf(0, 1, 47_999, 96_000, 1_000_000)) {
            assertEquals(timeline.trueNanos(frame), mapper.frameToNanos(frame).toDouble(), 2.0)
        }
        assertEquals(1_000_000.0, mapper.nanosToFrame(timeline.trueNanos(1_000_000).toLong()), 1e-3)
        assertEquals(0.0, mapper.driftPpm, 1e-6)
    }

    @Test
    fun jitteredTimestampsWithDriftStayWithinOneSample() {
        for (ppm in doubleArrayOf(-80.0, 0.0, 35.0, 120.0)) {
            val timeline = Timeline(nominal * (1 + ppm * 1e-6), startNanos = 123_456_789_000L, jitterNanos = 50_000, seed = ppm.toLong())
            val mapper = AudioClockMapper(nominal, capacity = 128)
            feed(mapper, timeline, fromFrame = 9_600, count = 400) // 40 s of timestamps every 100 ms.
            assertEquals("ppm $ppm", ppm, mapper.driftPpm, 5.0)
            val last = 9_600L + 399 * 4_800
            for (frame in longArrayOf(last - 600_000, last - 4_800, last, last + 48_000)) {
                val error = abs(mapper.frameToNanos(frame) - timeline.trueNanos(frame))
                assertTrue("ppm $ppm frame $frame error $error ns", error < framePeriodNanos)
            }
        }
    }

    @Test
    fun outliersAreRejectedAndARestartIsDetected() {
        val timeline = Timeline(nominal.toDouble(), startNanos = 1_000_000_000L, jitterNanos = 0)
        val mapper = AudioClockMapper(nominal)
        feed(mapper, timeline, 0, 30)
        assertFalse(mapper.add(30 * 4_800L, timeline.read(30 * 4_800L) + 15_000_000L)) // 15 ms late: a scheduling glitch.
        assertEquals(1, mapper.rejected)
        assertEquals(timeline.trueNanos(200_000), mapper.frameToNanos(200_000).toDouble(), 2.0)
        // The stream restarts: positions go back to 0 at a new time base.
        val restarted = Timeline(nominal.toDouble(), startNanos = 90_000_000_000L, jitterNanos = 0)
        feed(mapper, restarted, 0, 10)
        assertEquals(10, mapper.points)
        assertEquals(restarted.trueNanos(50_000), mapper.frameToNanos(50_000).toDouble(), 2.0)
    }

    @Test
    fun wavOffsetAlignsTheFirstVideoFrameIncludingTheChainLatency() {
        val timeline = Timeline(nominal.toDouble(), startNanos = 2_000_000_000L, jitterNanos = 0)
        val mapper = AudioClockMapper(nominal)
        feed(mapper, timeline, 0, 10)
        // Video starts 250 ms after audio frame 0; the WAV starts at position 0 and the chain delays by 96 frames.
        val videoStart = 2_250_000_000L
        assertEquals(12_000L, mapper.wavStartOffsetFrames(videoStart, firstFramePosition = 0))
        assertEquals(12_096L, mapper.wavStartOffsetFrames(videoStart, firstFramePosition = 0, processingLatencyFrames = 96))
        // Audio started after the video: negative, i.e. prepend silence.
        assertEquals(-2_400L, mapper.wavStartOffsetFrames(videoStart, firstFramePosition = 14_400))
    }

    @Test
    fun driftFramesTellHowFarANominalRateWavWanders() {
        val timeline = Timeline(nominal * (1 + 50e-6), startNanos = 0, jitterNanos = 0)
        val mapper = AudioClockMapper(nominal)
        feed(mapper, timeline, 0, 50)
        assertEquals(50.0, mapper.driftPpm, 0.01)
        assertEquals(nominal * (1 + 50e-6), mapper.measuredSampleRate, 1e-3)
        // One hour at +50 ppm: 48 000 · 50e-6 · 3600 = 8 640 frames (180 ms).
        assertEquals(8_640.0, mapper.driftFrames(3_600_000_000_000L), 1.0)
    }
}
