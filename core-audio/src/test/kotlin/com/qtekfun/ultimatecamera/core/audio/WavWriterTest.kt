package com.qtekfun.ultimatecamera.core.audio

import java.io.File
import java.io.FileOutputStream
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
    fun headerIsPatchedWhileWritingSoACutShortFileStillPlays() {
        val file = File.createTempFile("test", ".wav")
        val wav = WavWriter(file, 48_000, 1, headerEveryBytes = 100)
        wav.write(ShortArray(40) { 7 }, 40) // 80 bytes: not patched yet.
        assertEquals(0, ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
        wav.write(ShortArray(40) { 7 }, 40) // 160 bytes: patched.
        val header = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(160, header.getInt(40))
        assertEquals(36 + 160, header.getInt(4))
        wav.close()
        file.delete()
    }

    @Test
    fun writesFromAnOffsetIntoAnOpenDescriptorAndLeavesItOpen() {
        val file = File.createTempFile("test", ".wav")
        FileOutputStream(file).use { out ->
            WavWriter(out.fd, 44_100, 2).use { it.write(shortArrayOf(1, 2, 3, 4, 5), 2, 3) }
            out.write(byteArrayOf()) // Still open: the owner closes it.
        }
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 6, bytes.size)
        assertEquals(6, buffer.getInt(40))
        assertEquals(3, buffer.getShort(44).toInt())
        assertEquals(5, buffer.getShort(48).toInt())
        file.delete()
    }

    @Test
    fun hugeSizesAreCappedToWhatRiffCanHold() {
        val header = ByteBuffer.wrap(WavWriter.header(48_000, 2, 10L * 1024 * 1024 * 1024)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(WavWriter.MAX_DATA_BYTES, header.getInt(40).toLong() and 0xFFFF_FFFFL)
        assertEquals(0xFFFF_FFFFL, header.getInt(4).toLong() and 0xFFFF_FFFFL)
    }

    @Test
    fun levelMeterReportsFullScaleAndSilence() {
        val meter = LevelMeter()
        meter.add(shortArrayOf(Short.MIN_VALUE, 0), 2)
        assertEquals(0.0, meter.peakDbfs, 1e-9)
        assertEquals(Double.NEGATIVE_INFINITY, LevelMeter().apply { add(ShortArray(10), 10) }.peakDbfs, 0.0)
    }
}
