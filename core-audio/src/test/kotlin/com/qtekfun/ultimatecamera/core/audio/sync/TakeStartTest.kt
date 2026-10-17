package com.qtekfun.ultimatecamera.core.audio.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TakeStartTest {
    private val rate = 48_000
    private val frames = 960 // 20 ms

    @Test
    fun aBufferBeforeTheStartIsDroppedWhole() {
        assertNull(TakeStart.framesToSkip(bufferNanos = 0, frames = frames, startNanos = 20_000_000, sampleRate = rate))
        assertNull(TakeStart.framesToSkip(bufferNanos = 0, frames = frames, startNanos = 50_000_000, sampleRate = rate))
    }

    @Test
    fun theBufferThatCrossesTheStartIsTrimmedToTheSample() {
        // Start 12.5 ms into the buffer: 600 frames at 48 kHz.
        assertEquals(600, TakeStart.framesToSkip(bufferNanos = 1_000_000_000, frames = frames, startNanos = 1_012_500_000, sampleRate = rate))
        assertEquals(1, TakeStart.framesToSkip(bufferNanos = 0, frames = frames, startNanos = 20_833, sampleRate = rate))
    }

    @Test
    fun aBufferAtOrAfterTheStartIsKeptWhole() {
        assertEquals(0, TakeStart.framesToSkip(bufferNanos = 5, frames = frames, startNanos = 5, sampleRate = rate))
        assertEquals(0, TakeStart.framesToSkip(bufferNanos = 9, frames = frames, startNanos = 5, sampleRate = rate))
    }
}
