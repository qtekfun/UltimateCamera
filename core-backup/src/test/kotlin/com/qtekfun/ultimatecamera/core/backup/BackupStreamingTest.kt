package com.qtekfun.ultimatecamera.core.backup

import com.qtekfun.ultimatecamera.core.capabilities.DeviceIdentity
import com.qtekfun.ultimatecamera.core.luts.LutBackupAccess
import com.qtekfun.ultimatecamera.core.luts.LutBackupView
import com.qtekfun.ultimatecamera.core.luts.LutEntry
import com.qtekfun.ultimatecamera.core.luts.LutFormatName
import com.qtekfun.ultimatecamera.core.luts.LutOrigin
import com.qtekfun.ultimatecamera.core.luts.StagedLut
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A LUT file larger than any buffer goes through export and import in pieces: the source is generated on the fly (it
 * never exists in memory), the backup file grows on disk while the source is still being read, and the import stages
 * it to disk with the right checksum.
 */
class BackupStreamingTest {
    @get:Rule
    val tmp = TemporaryFolder(File("build/tmp/backup-streaming-test").apply { mkdirs() })

    private val size = 48L * 1024 * 1024

    /** Deterministic pseudo-random bytes (incompressible, so the zip cannot shrink them away). */
    private class Generated(private val size: Long, private val onRead: (Long) -> Unit = {}) : InputStream() {
        private var position = 0L
        private var state = 0x2545F4914F6CDD1DL

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            val n = minOf(len.toLong(), size - position).toInt()
            for (i in off until off + n) {
                state = state xor (state shl 13)
                state = state xor (state ushr 7)
                state = state xor (state shl 17)
                b[i] = state.toByte()
            }
            position += n
            onRead(position)
            return n
        }
    }

    private class CountingFile(file: File) : OutputStream() {
        private val out = FileOutputStream(file)
        var written = 0L

        override fun write(b: Int) {
            out.write(b)
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            written += len
        }

        override fun close() = out.close()
    }

    private class FakeLuts(private val entry: LutEntry?, private val source: () -> InputStream) : LutBackupAccess {
        var staged: List<StagedLut> = emptyList()
        var stagedSha: String? = null
        var stagedLength = 0L

        override suspend fun backupView() = LutBackupView(emptyList(), listOfNotNull(entry)) { source() }

        override suspend fun restore(folders: List<String>, luts: List<StagedLut>, replace: Boolean): Map<String, String> {
            staged = luts
            stagedLength = luts.single().file.length()
            stagedSha = luts.single().file.inputStream().use(BackupWriter::sha256)
            return luts.associate { it.entry.id to it.entry.id }
        }
    }

    @Test
    fun aBigLutStreamsThroughExportAndImport() = runBlocking {
        for (password in listOf(null, "pw".toCharArray())) {
            val sha = Generated(size).use(BackupWriter::sha256)
            val entry = LutEntry("big", "Big", "", LutFormatName.CUBE, 65, "big.cube", LutOrigin.generated, sha, size, 0)
            val file = File(tmp.root, "big-${password != null}.zip")
            val sink = CountingFile(file)
            var writtenWhileReading = 0L
            val luts = FakeLuts(entry) {
                Generated(size) { read -> if (read >= size / 2 && writtenWhileReading == 0L) writtenWhileReading = sink.written }
            }
            val origin = BackupOrigin("1", 1, BackupDevice("m", "x", "f"), "2026-10-17T00:00:00Z")
            BackupWriter.write(sink, BackupSources(FakeSettings(), luts, null), origin, password)

            assertTrue("output grows before the input ends ($writtenWhileReading)", writtenWhileReading > size / 4)
            assertTrue(file.length() > size)

            val target = FakeLuts(null) { error("not read") }
            val identity = DeviceIdentity("m", "b", "x", "d", 37)
            BackupReader().restore({ file.inputStream() }, password, true, File(tmp.root, "staging"), RestoreTargets(FakeSettings(), target, null, identity))
            assertEquals(size, target.stagedLength)
            assertEquals(sha, target.stagedSha)
        }
    }
}
