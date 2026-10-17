package com.qtekfun.ultimatecamera.core.audio.dsp

import com.qtekfun.ultimatecamera.core.audio.dsp.ChannelMap.MonoSource
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChainTest {
    private val rate = Signals.RATE

    private fun shorts(signal: FloatArray) = ShortArray(signal.size) { (signal[it] * 32767).roundToInt().toShort() }

    @Test
    fun sixteenBitStereoWithGainIsLimitedBelowTheCeiling() {
        val chain = AudioChain(rate, ChannelMap.identity(2), maxFrames = 480, gainDb = 24.0)
        val ceiling = (Db.toLinear(-1.0) * 32768).roundToInt() // Within half an LSB of −1 dBFS.
        val signal = shorts(Signals.sweep(rate * 3, 2, 30.0, 15_000.0, amplitude = 0.5))
        var maxOut = 0
        val buffer = ShortArray(960)
        for (f in 0 until rate * 3 step 480) {
            System.arraycopy(signal, f * 2, buffer, 0, 960)
            assertEquals(960, chain.process(buffer, 480))
            for (s in buffer) maxOut = maxOf(maxOut, abs(s.toInt()))
        }
        assertTrue("max $maxOut vs ceiling $ceiling", maxOut <= ceiling && maxOut > ceiling * 0.97)
        val snapshot = MeterSnapshot()
        assertTrue(chain.meter.read(snapshot))
        assertEquals(-1.0, snapshot.holdDbfs(0), 0.1)
        assertFalse("no input clip", snapshot.clip[0])
    }

    @Test
    fun unityChainIsTransparentApartFromTheDelay() {
        val chain = AudioChain(rate, ChannelMap.identity(1), maxFrames = 1024)
        val input = shorts(Signals.sine(1024, 1, 440.0, 0.5))
        val buffer = input.copyOf()
        chain.process(buffer, 1024)
        val l = chain.latencyFrames
        for (i in 0 until 1024 - l) assertEquals(input[i], buffer[i + l])
    }

    @Test
    fun monoFromStereoFlagsInputClipOnlyFromTheRecordedChannel() {
        val chain = AudioChain(rate, ChannelMap.mono(2, MonoSource.LEFT), maxFrames = 4)
        assertEquals(1, chain.outputChannels)
        val buffer = shortArrayOf(1000, Short.MAX_VALUE, 2000, 0, 3000, 0, 4000, 0)
        assertEquals(4, chain.process(buffer, 4))
        val snapshot = MeterSnapshot(1)
        chain.meter.read(snapshot)
        assertFalse("right clipped but is not recorded", snapshot.clip[0])

        val stereoToMono = AudioChain(rate, ChannelMap.mono(2, MonoSource.RIGHT), maxFrames = 4)
        stereoToMono.process(buffer.copyOf().also { it[1] = Short.MAX_VALUE }, 4)
        stereoToMono.meter.read(snapshot)
        assertTrue("right clipped and is recorded", snapshot.clip[0])
    }

    @Test
    fun floatPathPicksChannelsOfAMultichannelInterface() {
        val chain = AudioChain(rate, ChannelMap.pick(4, 3, 1), maxFrames = 200)
        val buffer = FloatArray(800) { i ->
            if (i % 4 == 3) {
                0.25f
            } else if (i % 4 == 1) {
                -0.5f
            } else {
                0.9f
            }
        }
        assertEquals(400, chain.process(buffer, 200))
        val l = chain.latencyFrames
        assertEquals(0.25f, buffer[2 * l], 0f)
        assertEquals(-0.5f, buffer[2 * l + 1], 0f)
    }

    @Test
    fun processingDoesNotAllocatePerBuffer() {
        val stereo = AudioChain(rate, ChannelMap.identity(2), maxFrames = 480, gainDb = 18.0)
        val mono = AudioChain(rate, ChannelMap.mono(2, MonoSource.MIX), maxFrames = 480, gainDb = 6.0)
        val pcm = shorts(Signals.sine(480, 2, 1000.0, 0.9))
        val floats = Signals.sine(480, 2, 1000.0, 0.9)
        val snapshot = MeterSnapshot()
        val warm = {
            stereo.process(pcm, 480)
            mono.process(floats, 480)
            stereo.gain.gainDb = if (stereo.gain.gainDb > 10) 0.0 else 18.0
            stereo.meter.read(snapshot)
        }
        repeat(2000) { warm() }
        val bytes = Signals.allocatedBytes { repeat(5000) { warm() } }
        assertTrue("allocated $bytes bytes over 5000 buffers", bytes < 1024)
    }

    @Test
    fun sixtySecondsOfStereoRunFarFasterThanRealTime() {
        val chain = AudioChain(rate, ChannelMap.identity(2), maxFrames = 480, gainDb = 12.0)
        val source = shorts(Signals.sweep(rate, 2, 40.0, 12_000.0, amplitude = 0.7))
        val buffer = ShortArray(960)
        val buffersPerSecond = rate / 480
        fun run(seconds: Int) {
            for (k in 0 until seconds * buffersPerSecond) {
                System.arraycopy(source, (k % buffersPerSecond) * 960, buffer, 0, 960)
                chain.process(buffer, 480)
            }
        }
        run(10) // Warm-up (JIT).
        val start = System.nanoTime()
        run(60)
        val seconds = (System.nanoTime() - start) / 1e9
        val speed = 60 / seconds
        println("AudioChain: 60 s of 48 kHz stereo 16-bit in ${"%.3f".format(seconds)} s (${speed.roundToInt()}x real time)")
        assertTrue("only ${speed}x real time", speed > 50)
    }
}
