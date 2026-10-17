package com.qtekfun.ultimatecamera.core.audio.dsp

import com.qtekfun.ultimatecamera.core.audio.dsp.ChannelMap.MonoSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMapTest {
    /** Frame f, channel c holds f * 10 + c + 1. */
    private fun ramp(frames: Int, channels: Int, extra: Int = 0) = FloatArray(frames * (channels + extra)) { i ->
        if (i < frames * channels) ((i / channels) * 10 + i % channels + 1).toFloat() else -1f
    }

    @Test
    fun monoFromLeftRightOrMix() {
        val left = ramp(3, 2).also { ChannelMap.mono(2, MonoSource.LEFT).apply(it, 3) }
        assertArrayEquals(floatArrayOf(1f, 11f, 21f), left.copyOf(3), 0f)
        val right = ramp(3, 2).also { ChannelMap.mono(2, MonoSource.RIGHT).apply(it, 3) }
        assertArrayEquals(floatArrayOf(2f, 12f, 22f), right.copyOf(3), 0f)
        val mix = ramp(3, 2).also { ChannelMap.mono(2, MonoSource.MIX).apply(it, 3) }
        assertArrayEquals(floatArrayOf(1.5f, 11.5f, 21.5f), mix.copyOf(3), 0f)
    }

    @Test
    fun monoToStereoExpandsInPlace() {
        val buffer = ramp(4, 1, extra = 1)
        val map = ChannelMap.stereo(1)
        assertEquals(2, map.outputChannels)
        map.apply(buffer, 4)
        assertArrayEquals(floatArrayOf(1f, 1f, 11f, 11f, 21f, 21f, 31f, 31f), buffer, 0f)
    }

    @Test
    fun picksChannelsFromAMultichannelInterface() {
        // 8-channel USB interface, record inputs 3 and 6 (0-based 2 and 5) as stereo, and swapped.
        val buffer = ramp(5, 8)
        ChannelMap.pick(8, 2, 5).apply(buffer, 5)
        assertArrayEquals(floatArrayOf(3f, 6f, 13f, 16f, 23f, 26f, 33f, 36f, 43f, 46f), buffer.copyOf(10), 0f)
        val swapped = ramp(2, 2).also { ChannelMap.pick(2, 1, 0).apply(it, 2) }
        assertArrayEquals(floatArrayOf(2f, 1f, 12f, 11f), swapped, 0f)
        // Expand 2 → 4 (duplicate pairs) in place, backwards.
        val wide = ramp(3, 2, extra = 2)
        ChannelMap.pick(2, 0, 1, 1, 0).apply(wide, 3)
        assertArrayEquals(floatArrayOf(1f, 2f, 2f, 1f, 11f, 12f, 12f, 11f, 21f, 22f, 22f, 21f), wide, 0f)
    }

    @Test
    fun identityIsANoOpAndMasksTrackSources() {
        val id = ChannelMap.identity(2)
        assertTrue(id.isIdentity)
        assertFalse(ChannelMap.pick(2, 1, 0).isIdentity)
        val mix = ChannelMap.mono(2, MonoSource.MIX)
        assertEquals(0b11L, mix.sourceMask(0))
        assertEquals(0b100000L, ChannelMap.pick(8, 2, 5).sourceMask(1))
    }

    @Test
    fun rejectsInvalidMappings() {
        assertThrows(IllegalArgumentException::class.java) { ChannelMap.pick(2, 2) }
        assertThrows(IllegalArgumentException::class.java) { ChannelMap.matrix(2, 1, floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { ChannelMap.identity(MAX_AUDIO_CHANNELS + 1) }
    }
}
