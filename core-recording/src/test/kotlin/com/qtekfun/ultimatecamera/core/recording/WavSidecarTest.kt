package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.audio.WavWriter
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavSidecarTest {
    @get:Rule val temp = TemporaryFolder()

    /** An [OutputFile] over a real file, recording what the sidecar does with it. */
    private class FileOutput(val file: File, private val failSync: Boolean = false) : OutputFile {
        private val stream = FileOutputStream(file)
        val events = mutableListOf<String>()
        override val uri: String = file.toURI().toString()
        override val fileDescriptor: FileDescriptor = stream.fd

        override fun write(buffer: ByteArray, offset: Int, length: Int) = stream.write(buffer, offset, length)

        override fun sync() {
            events += "sync"
            if (failSync) throw IOException("sync failed")
            stream.fd.sync()
        }

        override fun close() {
            events += "close"
            stream.close()
        }

        override fun delete() {
            events += "delete"
            stream.close()
            file.delete()
        }
    }

    private val config = AudioConfig(sampleRate = 48_000, channels = 2)

    @Test
    fun aKeptWavIsAValidPcmFileWithTheSamples() {
        val out = FileOutput(temp.newFile("take.wav"))
        val sidecar = WavSidecar(out, config)
        val pcm = ShortArray(480) { (it * 64).toShort() }
        sidecar.writer.write(pcm, 0, pcm.size)
        sidecar.finish(keep = true)
        assertEquals(listOf("sync", "close"), out.events)
        val bytes = out.file.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        val data = ByteBuffer.wrap(bytes, bytes.size - pcm.size * 2, pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val read = ShortArray(pcm.size).also { data.get(it) }
        assertArrayEquals(pcm, read)
    }

    @Test
    fun theHandoverBetweenPartsIsSilenceSoTheWavKeepsTheTakesTimeline() {
        val out = FileOutput(temp.newFile("take.wav"))
        val sidecar = WavSidecar(out, config)
        val part1 = ShortArray(960) { 100 }
        val part2 = ShortArray(960) { 200 }
        sidecar.writer.write(part1, 0, part1.size)
        sidecar.padSilence(frames = 1_200) // 25 ms microphone handover.
        sidecar.writer.write(part2, 0, part2.size)
        val report = sidecar.finish(keep = true)
        val bytes = out.file.readBytes()
        val pcm = ByteBuffer.wrap(bytes, WavWriter.HEADER_SIZE, bytes.size - WavWriter.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(960 + 2_400 + 960, pcm.remaining())
        assertEquals(100.toShort(), pcm.get(959))
        assertEquals(0.toShort(), pcm.get(960))
        assertEquals(0.toShort(), pcm.get(960 + 2_399))
        assertEquals(200.toShort(), pcm.get(960 + 2_400))
        assertEquals((960 + 2_400 + 960) * 2, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(WavWriter.HEADER_SIZE - 4))
        assertEquals(WavReport(0, 0, 48_000, null, kept = true), report)
        assertFalse(report.hasGaps)
        assertEquals(0L, sidecar.droppedFrames)
    }

    @Test
    fun aReportWithDroppedFramesTellsTheSecondsOfSilence() {
        val report = WavReport(droppedFrames = 24_000, overruns = 2, sampleRate = 48_000, error = null, kept = true)
        assertTrue(report.hasGaps)
        assertEquals(0.5, report.droppedSeconds, 1e-9)
        assertFalse(report.copy(kept = false).hasGaps)
        assertFalse(report.copy(droppedFrames = 0).hasGaps)
    }

    @Test
    fun noErrorIsReportedForAHealthyWav() {
        val out = FileOutput(temp.newFile("take.wav"))
        val sidecar = WavSidecar(out, config)
        assertEquals(null, sidecar.newError())
        assertEquals(null, sidecar.finish(keep = true).error)
    }

    @Test
    fun aDroppedWavIsDeleted() {
        val out = FileOutput(temp.newFile("take.wav"))
        WavSidecar(out, config).finish(keep = false)
        assertEquals(listOf("delete"), out.events)
        assertFalse(out.file.exists())
    }

    @Test
    fun aFailingSyncStillClosesTheFile() {
        val out = FileOutput(temp.newFile("take.wav"), failSync = true)
        WavSidecar(out, config).finish(keep = true)
        assertEquals(listOf("sync", "close"), out.events)
        assertTrue(out.file.exists())
    }

    @Test
    fun discardDeletesAndToleratesNothing() {
        val out = FileOutput(temp.newFile("unused.wav"))
        WavSidecar.discard(out)
        assertEquals(listOf("delete"), out.events)
        WavSidecar.discard(null)
    }

    @Test
    fun mimeTypeIsTheOneAndroidMapsToWav() {
        assertEquals("audio/x-wav", WavSidecar.MIME_TYPE)
    }
}
