package com.qtekfun.ultimatecamera.core.audio.dsp

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainProcessorTest {
    @Test
    fun appliesTheGainInDbExactlyOnceSettled() {
        for (db in doubleArrayOf(-20.0, -6.0, 0.0, 6.0, 12.0, 24.0)) {
            val gain = GainProcessor(2, Signals.RATE, gainDb = db)
            val buffer = FloatArray(200) { 0.01f }
            gain.process(buffer, 100)
            val measured = Db.fromLinear(buffer[0] / 0.01)
            assertEquals("gain $db dB", db, measured, 1e-4)
        }
    }

    @Test
    fun aGainChangeIsSmoothedWithoutSteps() {
        val gain = GainProcessor(1, Signals.RATE, gainDb = 0.0)
        gain.gainDb = 12.0
        val frames = Signals.RATE / 2
        val buffer = FloatArray(frames) { 0.1f }
        gain.process(buffer, frames)
        // Monotonic rise, no sample-to-sample step larger than the smoother allows (first-sample step ≈ 3.98·0.1/960).
        var maxStep = 0f
        for (i in 1 until frames) {
            val step = buffer[i] - buffer[i - 1]
            assertTrue("monotonic at $i", step >= 0f)
            maxStep = maxOf(maxStep, step)
        }
        assertTrue("first sample already moved but only slightly: ${buffer[0]}", buffer[0] > 0.1f && buffer[0] < 0.1005f)
        assertTrue("max step $maxStep", maxStep < 0.0004f)
        // 20 ms time constant: 63 % of the way after 20 ms, settled (< 0.01 dB) after 200 ms.
        val at20ms = buffer[Signals.RATE / 50 - 1] / 0.1f
        assertEquals(1 + (3.981f - 1) * 0.632f, at20ms, 0.02f)
        assertEquals(12.0, Db.fromLinear(buffer[Signals.RATE / 5] / 0.1), 0.01)
        assertEquals(Db.toLinear(12.0), gain.appliedLinear, 0.0)
    }

    @Test
    fun smoothingCarriesAcrossBuffers() {
        val oneBuffer = GainProcessor(2, Signals.RATE)
        val split = GainProcessor(2, Signals.RATE)
        oneBuffer.gainDb = -12.0
        split.gainDb = -12.0
        val a = Signals.sine(4800, 2, 440.0, 0.5)
        val b = a.copyOf()
        oneBuffer.process(a, 4800)
        for (k in 0 until 10) {
            val part = b.copyOfRange(k * 960, (k + 1) * 960)
            split.process(part, 480)
            part.copyInto(b, k * 960)
        }
        for (i in a.indices) assertTrue("sample $i", abs(a[i] - b[i]) < 1e-6f)
    }

    @Test
    fun gainIsClampedToItsRange() {
        val gain = GainProcessor(1, Signals.RATE)
        gain.gainDb = 200.0
        assertEquals(GainProcessor.MAX_DB, gain.gainDb, 0.0)
        gain.gainDb = -200.0
        assertEquals(GainProcessor.MIN_DB, gain.gainDb, 0.0)
    }
}
