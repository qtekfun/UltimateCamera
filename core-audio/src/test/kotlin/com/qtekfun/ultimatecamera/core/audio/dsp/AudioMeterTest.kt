package com.qtekfun.ultimatecamera.core.audio.dsp

import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMeterTest {
    private val rate = Signals.RATE

    private fun feed(meter: AudioMeter, signal: FloatArray, chunkFrames: Int = 480) {
        val ch = meter.channels
        val chunk = FloatArray(chunkFrames * ch)
        var f = 0
        while (f < signal.size / ch) {
            val n = minOf(chunkFrames, signal.size / ch - f)
            System.arraycopy(signal, f * ch, chunk, 0, n * ch)
            meter.process(chunk, n)
            f += n
        }
    }

    private fun snapshot(meter: AudioMeter) = MeterSnapshot(meter.channels).also { assertTrue(meter.read(it)) }

    @Test
    fun knownSinesReadTheirPeakAndRms() {
        val meter = AudioMeter(2, rate)
        val left = Signals.sine(rate * 2, 1, 997.0, 1.0)
        val right = Signals.sine(rate * 2, 1, 997.0, Db.toLinear(-20.0))
        feed(meter, FloatArray(rate * 4) { if (it % 2 == 0) left[it / 2] else right[it / 2] })
        val s = snapshot(meter)
        assertEquals(2, s.channels)
        assertEquals(0.0, s.peakDbfs(0), 0.05)
        assertEquals(-3.01, s.rmsDbfs(0), 0.05)
        assertEquals(-20.0, s.peakDbfs(1), 0.05)
        assertEquals(-23.01, s.rmsDbfs(1), 0.05)
        assertEquals(0.0, s.holdDbfs(0), 0.01)
    }

    @Test
    fun silenceReadsMinusInfinity() {
        val meter = AudioMeter(1, rate)
        feed(meter, FloatArray(rate))
        assertEquals(Double.NEGATIVE_INFINITY, snapshot(meter).peakDbfs(0), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, snapshot(meter).rmsDbfs(0), 0.0)
    }

    @Test
    fun peakFallsAtTheIecRateAndHoldStaysThenFalls() {
        val meter = AudioMeter(1, rate)
        val burst = Signals.sine(rate / 10, 1, 1000.0, 1.0)
        feed(meter, burst)
        feed(meter, FloatArray(rate)) // 1 s of silence.
        var s = snapshot(meter)
        assertEquals(-20.0 / 1.7, s.peakDbfs(0), 0.2)
        assertEquals("hold still up after 1 s", 0.0, s.holdDbfs(0), 0.01)
        feed(meter, FloatArray(rate)) // 2 s after the burst: hold expired at 1.5 s, then 0.5 s of fall.
        s = snapshot(meter)
        assertEquals(-40.0 / 1.7, s.peakDbfs(0), 0.2)
        assertEquals(-10.0 / 1.7, s.holdDbfs(0), 0.2)
    }

    @Test
    fun rmsIntegratesWithItsTimeConstant() {
        val meter = AudioMeter(1, rate)
        feed(meter, FloatArray(rate * 3 / 10) { 0.5f }) // DC step, 300 ms = one time constant.
        assertEquals(0.5 * kotlin.math.sqrt(1 - kotlin.math.exp(-1.0)), snapshot(meter).rmsLinear[0].toDouble(), 2e-3)
    }

    @Test
    fun clipFlagIsLatchedForItsHoldTimeAndCounted() {
        val meter = AudioMeter(2, rate)
        meter.markClipped(0b10)
        meter.process(FloatArray(960), 480)
        var s = snapshot(meter)
        assertFalse(s.clip[0])
        assertTrue(s.clip[1])
        assertEquals(1, s.clipCount[1])
        feed(meter, FloatArray(rate * 2 * 19 / 10))
        assertTrue("still within 2 s", snapshot(meter).clip[1])
        feed(meter, FloatArray(rate * 2 * 2 / 10))
        s = snapshot(meter)
        assertFalse("cleared after 2 s", s.clip[1])
        assertEquals(1, s.clipCount[1])
    }

    @Test
    fun readerAlwaysSeesAConsistentSnapshot() {
        // The writer publishes a constant per buffer on every channel; a torn read would mix two buffers.
        val meter = AudioMeter(8, rate, decayDbPerSecond = 1e9, holdSeconds = 0.0)
        val buffer = FloatArray(8 * 64)
        val writer = thread {
            for (k in 1..20_000) {
                buffer.fill((k % 1000 + 1) / 1000f)
                meter.process(buffer, 64)
            }
        }
        val s = MeterSnapshot(8)
        var reads = 0
        while (writer.isAlive) {
            if (meter.read(s) && s.channels == 8) {
                reads++
                for (c in 1 until 8) assertEquals(s.peakLinear[0], s.peakLinear[c], 0f)
            }
        }
        writer.join()
        assertTrue("reads $reads", reads > 0)
    }

    @Test
    fun readDoesNotAllocate() {
        val meter = AudioMeter(2, rate)
        val snapshot = MeterSnapshot(2)
        meter.process(FloatArray(960) { 0.5f }, 480)
        repeat(1000) { meter.read(snapshot) }
        val bytes = Signals.allocatedBytes {
            repeat(10_000) {
                meter.read(snapshot)
                snapshot.peakDbfs(0)
            }
        }
        assertTrue("allocated $bytes bytes", bytes < 1024)
    }
}
