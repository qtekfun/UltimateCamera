package com.qtekfun.ultimatecamera.core.recording

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
