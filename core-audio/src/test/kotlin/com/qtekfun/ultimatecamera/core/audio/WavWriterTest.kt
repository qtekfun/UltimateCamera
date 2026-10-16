package com.qtekfun.ultimatecamera.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavWriterTest {
    @Test
    fun writesAValidHeaderWithTheFinalSizes() {
        val file = File.createTempFile("test", ".wav")
        WavWriter(file, 48_000, 2).use { it.write(ShortArray(480) { i -> i.toShort() }, 480) }
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 960, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + 960, header.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(2, header.getShort(22).toInt())
        assertEquals(48_000, header.getInt(24))
        assertEquals(48_000 * 2 * 2, header.getInt(28))
        assertEquals(960, header.getInt(40))
        assertEquals(5, header.getShort(44 + 10).toInt()) // Sample 5, little-endian.
        file.delete()
    }

    @Test
    fun levelMeterReportsFullScaleAndSilence() {
        val meter = LevelMeter()
        meter.add(shortArrayOf(Short.MIN_VALUE, 0), 2)
        assertEquals(0.0, meter.peakDbfs, 1e-9)
        assertEquals(Double.NEGATIVE_INFINITY, LevelMeter().apply { add(ShortArray(10), 10) }.peakDbfs, 0.0)
    }
}
