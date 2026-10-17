package com.qtekfun.ultimatecamera.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BextChunkTest {
    private val s = 1_000_000_000L

    /** 2026-10-17 00:00:00 UTC. */
    private val midnight = 1_792_195_200L * s

    @Test
    fun timeReferenceCountsSamplesSinceLocalMidnight() {
        val t = midnight + (10 * 3_600 + 11 * 60 + 12) * s + 500_000_000L
        assertEquals((10 * 3_600 + 11 * 60 + 12) * 48_000L + 24_000, BextChunk.samplesSinceMidnight(t, 0, 48_000))
        // Two hours east of UTC: 12:11:12.5 local.
        assertEquals((12 * 3_600 + 11 * 60 + 12) * 48_000L + 24_000, BextChunk.samplesSinceMidnight(t, 7_200, 48_000))
        assertEquals(0, BextChunk.samplesSinceMidnight(midnight, 0, 48_000))
        assertEquals(86_400L * 48_000 - 1, BextChunk.samplesSinceMidnight(midnight - 20_834, 0, 48_000)) // Rounded to the nearest sample.
        // A high rate late in the day does not overflow.
        assertEquals(86_399L * 192_000, BextChunk.samplesSinceMidnight(midnight - s, 0, 192_000))
    }

    @Test
    fun broadcastWaveRoundTrips() {
        val file = File.createTempFile("test", ".wav")
        val bext = BextChunk(
            description = "UltimateCamera A001_T001 TC 10:11:12:15 @30",
            originatorReference = "A001_T001",
            originationDate = "2026-10-17",
            originationTime = "10:11:12",
            timeReferenceSamples = 1_761_624_000L
        )
        WavWriter(file, 48_000, 2, bext = bext).use { it.write(ShortArray(480) { i -> i.toShort() }, 480) }
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val size = WavWriter.headerSize(broadcast = true)
        assertEquals(44 + BextChunk.CHUNK_SIZE, size)
        assertEquals(size + 960, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(bytes.size - 8, header.getInt(4))
        assertEquals("bext", String(bytes, 12, 4))
        assertEquals(BextChunk.BODY_SIZE, header.getInt(16))
        assertEquals("fmt ", String(bytes, 12 + BextChunk.CHUNK_SIZE, 4))
        assertEquals("data", String(bytes, size - 8, 4))
        assertEquals(960, header.getInt(size - 4))
        assertEquals(5, header.getShort(size + 10).toInt())
        assertEquals(bext.copy(originator = BextChunk.ORIGINATOR), BextChunk.parse(bytes))
        file.delete()
    }

    @Test
    fun timeReferenceAboveThirtyTwoBitsSurvives() {
        val bext = BextChunk(timeReferenceSamples = 0x1_2345_6789L)
        val bytes = WavWriter.header(96_000, 1, 0, bext)
        assertEquals(0x1_2345_6789L, BextChunk.parse(bytes)?.timeReferenceSamples)
    }

    @Test
    fun longTextIsCutAndPlainWavHasNoBext() {
        val long = "x".repeat(300)
        assertEquals(256, BextChunk.parse(WavWriter.header(48_000, 2, 0, BextChunk(description = long)))?.description?.length)
        assertNull(BextChunk.parse(WavWriter.header(48_000, 2, 0)))
        assertNull(BextChunk.parse(ByteArray(4)))
    }
}
