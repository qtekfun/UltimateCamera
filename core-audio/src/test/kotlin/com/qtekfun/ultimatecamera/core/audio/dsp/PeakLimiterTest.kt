package com.qtekfun.ultimatecamera.core.audio.dsp

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeakLimiterTest {
    private val ceiling = Db.toLinear(-1.0).toFloat()

    private fun processInChunks(limiter: PeakLimiter, signal: FloatArray, chunkFrames: Int = 480): FloatArray {
        val ch = limiter.channels
        val out = signal.copyOf()
        val chunk = FloatArray(chunkFrames * ch)
        var f = 0
        val total = signal.size / ch
        while (f < total) {
            val n = minOf(chunkFrames, total - f)
            System.arraycopy(out, f * ch, chunk, 0, n * ch)
            limiter.process(chunk, n)
            System.arraycopy(chunk, 0, out, f * ch, n * ch)
            f += n
        }
        return out
    }

    @Test
    fun latencyIsTheLookAhead() {
        val limiter = PeakLimiter(1, Signals.RATE)
        assertEquals(96, limiter.latencyFrames)
        val signal = FloatArray(1000)
        signal[10] = 0.5f
        val out = processInChunks(limiter, signal, 64)
        assertEquals(0.5f, out[10 + 96], 0f)
        assertEquals(1, out.count { it != 0f })
    }

    @Test
    fun quietSignalPassesUntouched() {
        val limiter = PeakLimiter(2, Signals.RATE)
        val signal = Signals.sine(48_000, 2, 1000.0, 0.8) // −1.9 dBFS, below the −1 dBFS ceiling.
        val out = processInChunks(limiter, signal)
        val l = limiter.latencyFrames * 2
        for (i in 0 until signal.size - l) assertEquals(signal[i], out[i + l], 0f)
    }

    @Test
    fun hardTransientsNeverExceedTheCeiling() {
        val limiter = PeakLimiter(2, Signals.RATE)
        val random = Random(42)
        val frames = 5 * Signals.RATE
        val signal = FloatArray(frames * 2)
        // Silence, then single-sample spikes, full-scale square bursts and +20 dB noise bursts.
        for (f in 0 until frames) {
            val v = when ((f / 4800) % 4) {
                0 -> if (f % 1200 == 600) 8f else 0f
                1 -> if ((f / 24) % 2 == 0) 4f else -4f
                2 -> (random.nextGaussian() * 3).toFloat()
                else -> 0.01f
            }
            signal[2 * f] = v
            signal[2 * f + 1] = -v * 0.5f
        }
        for (chunk in intArrayOf(1, 7, 480, 4096)) {
            limiter.reset()
            val out = processInChunks(limiter, signal, chunk)
            assertTrue("chunk $chunk: peak ${Signals.maxAbs(out)}", Signals.maxAbs(out) <= ceiling)
        }
    }

    @Test
    fun sineSweepAboveTheCeilingIsHeldAtIt() {
        val limiter = PeakLimiter(2, Signals.RATE)
        val signal = Signals.sweep(10 * Signals.RATE, 2, 20.0, 20_000.0, amplitude = 4.0) // +12 dBFS.
        val out = processInChunks(limiter, signal)
        val peak = Signals.maxAbs(out)
        assertTrue("peak $peak", peak <= ceiling)
        assertTrue("limited, not squashed: $peak", peak > ceiling * 0.98f)
    }

    @Test
    fun steadyLoudToneGetsAConstantGainWithoutPumping() {
        val limiter = PeakLimiter(1, Signals.RATE)
        for (hz in doubleArrayOf(50.0, 1000.0)) {
            limiter.reset()
            val signal = Signals.sine(2 * Signals.RATE, 1, hz, 2.0) // +6 dBFS.
            val out = processInChunks(limiter, signal)
            val l = limiter.latencyFrames
            // After settling (0.5 s), output / input is the same everywhere: a pure level change, no modulation.
            var minRatio = Float.MAX_VALUE
            var maxRatio = 0f
            for (i in Signals.RATE / 2 until signal.size - l) {
                if (abs(signal[i]) < 0.2f) continue
                val r = out[i + l] / signal[i]
                minRatio = minOf(minRatio, r)
                maxRatio = maxOf(maxRatio, r)
            }
            assertEquals("$hz Hz gain", ceiling / 2f, maxRatio, 1e-3f)
            assertTrue("$hz Hz modulation ${maxRatio / minRatio}", maxRatio / minRatio < 1.001f)
        }
    }

    @Test
    fun releaseRecoversWithItsTimeConstant() {
        val limiter = PeakLimiter(1, Signals.RATE)
        val frames = 2 * Signals.RATE
        val signal = FloatArray(frames) { 0.1f }
        val burstEnd = Signals.RATE / 10
        for (f in 0 until burstEnd) signal[f] = 4f // 100 ms at +12 dBFS, then a quiet −20 dBFS level.
        val out = processInChunks(limiter, signal)
        val l = limiter.latencyFrames
        fun gainAt(frame: Int) = out[frame + l] / signal[frame]
        val reduced = ceiling / 4f
        assertEquals(reduced, gainAt(burstEnd - 1), 1e-4f)
        // Held for 20 ms after the last loud sample (hold), then recovers: deficit falls to 1/e after 150 ms more.
        assertEquals(reduced, gainAt(burstEnd + 900), 2e-3f)
        val deficitStart = 1f - reduced
        val after = gainAt(burstEnd + 960 + 7200 + l)
        assertEquals(deficitStart * 0.3679f, 1f - after, 0.02f)
        assertEquals(1f, gainAt(frames - l - 1), 1e-3f)
        assertEquals(1f, limiter.currentGain, 1e-3f)
    }

    @Test
    fun disabledLimiterOnlyClampsFullScaleButKeepsTheDelay() {
        val limiter = PeakLimiter(1, Signals.RATE)
        limiter.enabled = false
        val signal = floatArrayOf(0.95f, 1.5f, -2f) + FloatArray(200)
        val out = processInChunks(limiter, signal)
        assertEquals(0.95f, out[96], 0f)
        assertEquals(1f, out[97], 0f)
        assertEquals(-1f, out[98], 0f)
    }
}
