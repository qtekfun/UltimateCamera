package com.qtekfun.ultimatecamera.core.codec.mp4

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4PostProcessorTest {
    // Under the module's build directory: on disk, never in /tmp (tmpfs).
    @get:Rule
    val dir = TemporaryFolder(File("build").apply { mkdirs() })

    private val start = TimecodeStart.ofTimeOfDay(epochNanos = 1_792_239_005_500_000_000L, utcOffsetSeconds = 7200, numerator = 30)
    private val options = PostProcessOptions(timecode = start, creationEpochSeconds = 1_792_239_005L)

    private fun process(file: File, opts: PostProcessOptions = options, wrap: (SeekableFile) -> SeekableFile = { it }): PostProcessResult {
        val (raf, f) = Mp4Fixture.open(file)
        return raf.use { Mp4PostProcessor.process(wrap(f), opts) }
    }

    private fun inspect(file: File): Mp4Summary {
        val (raf, f) = Mp4Fixture.open(file)
        return raf.use { Mp4Inspector.inspect(f) }
    }

    /** Video sample bytes as the (new) sample table finds them: offsets must still point at the same data. */
    private fun videoSamples(file: File): List<Int> {
        val (raf, f) = Mp4Fixture.open(file)
        raf.use {
            val moov = BoxReader.children(f, 0, f.size()).single { it.type == "moov" }
            val trak = BoxReader.children(f, moov).first { it.type == "trak" }
            var level = BoxReader.children(f, trak)
            for (type in listOf("mdia", "minf", "stbl")) level = BoxReader.children(f, level.single { it.type == type })
            val stco = BoxReader.bytes(f, level.single { it.type == "stco" })
            val count = Bytes.u32(stco, 12).toInt()
            return (0 until count).map { i ->
                val b = ByteArray(Mp4Fixture.SAMPLE_SIZE).also { buf -> f.read(Bytes.u32(stco, 16 + 4 * i), buf) }
                assertTrue(b.all { it == b[0] })
                b[0].toInt()
            }
        }
    }

    @Test
    fun addsATimecodeTrackReferencedByTheVideoTrack() {
        val file = Mp4Fixture.write(dir.newFile("take.mp4"))
        val before = inspect(file)
        val originalSize = file.length()
        val oldMoovSize = BoxReader.children(Mp4Fixture.open(file).second, 0, originalSize).single { it.type == "moov" }.size

        val result = process(file)

        assertTrue(result.toString(), result is PostProcessResult.Done)
        val after = inspect(file)
        assertEquals(listOf("ftyp", "mdat", "free", "mdat", "moov"), after.topLevel)
        assertEquals(listOf("vide", "tmcd"), after.tracks.map { it.handler })
        assertEquals(2L, after.tracks[1].trackId)
        assertEquals(2L, after.tracks[0].timecodeRef)
        assertEquals(3L, after.nextTrackId)
        assertEquals(start, after.timecode)
        assertEquals("2026-10-17T12:10:05Z", after.creationDay)
        assertEquals("mp42", after.majorBrand)
        assertEquals(before.tracks[0].colourType, after.tracks[0].colourType)
        assertEquals(listOf(0, 1, 2), videoSamples(file))
        // The old moov keeps its size as free space; the appended part is the timecode mdat plus the new moov.
        val (raf, f) = Mp4Fixture.open(file)
        raf.use {
            val boxes = BoxReader.children(f, 0, f.size())
            assertEquals(oldMoovSize, boxes[2].size)
            assertEquals(12L, boxes[3].size)
            assertEquals(originalSize + 12 + boxes[4].size, f.size())
            assertEquals(originalSize + 12 + boxes[4].size - originalSize, (result as PostProcessResult.Done).addedBytes)
        }
    }

    @Test
    fun moovInTheReservedSpaceAtTheFrontIsFreedInPlaceAndOffsetsHold() {
        val file = Mp4Fixture.write(dir.newFile("front.mp4"), samples = 5, moovFirst = true, audio = true)
        assertEquals(listOf(0, 1, 2, 3, 4), videoSamples(file))

        assertTrue(process(file) is PostProcessResult.Done)

        val after = inspect(file)
        assertEquals(listOf("ftyp", "free", "free", "mdat", "mdat", "moov"), after.topLevel)
        assertEquals(listOf("vide", "soun", "tmcd"), after.tracks.map { it.handler })
        assertEquals(3L, after.tracks[2].trackId)
        assertEquals(4L, after.nextTrackId)
        assertEquals(listOf(0, 1, 2, 3, 4), videoSamples(file))
    }

    @Test
    fun quickTimeModeWritesTheQtBrandAndQuickTimeBoxes() {
        val file = Mp4Fixture.write(dir.newFile("take.mov"), audio = true)

        assertTrue(process(file, options.copy(quickTime = true)) is PostProcessResult.Done)

        val after = inspect(file)
        assertEquals("qt  ", after.majorBrand)
        val (raf, f) = Mp4Fixture.open(file)
        raf.use {
            val ftyp = BoxReader.bytes(f, BoxReader.children(f, 0, f.size()).first())
            assertEquals(24, ftyp.size)
            assertEquals(0x200L, Bytes.u32(ftyp, 12))
            assertEquals("qt  qt  ", String(ftyp, 16, 8, Charsets.ISO_8859_1))
        }
        assertTrue(after.tracks.all { it.component == "mhlr" })
        assertEquals("nclc", after.tracks[0].colourType)
        assertEquals(start, after.timecode)
        assertEquals(listOf(0, 1, 2), videoSamples(file))
    }

    @Test
    fun quickTimeWithoutTimecodeOnlyConvertsTheContainer() {
        val file = Mp4Fixture.write(dir.newFile("plain.mov"))

        assertTrue(process(file, PostProcessOptions(quickTime = true)) is PostProcessResult.Done)

        val after = inspect(file)
        assertEquals(listOf("vide"), after.tracks.map { it.handler })
        assertNull(after.timecode)
        assertNull(after.tracks[0].timecodeRef)
        assertEquals(listOf("ftyp", "mdat", "free", "moov"), after.topLevel)
    }

    @Test
    fun aFailureWhileAppendingLeavesTheOriginalFile() {
        val file = Mp4Fixture.write(dir.newFile("fail.mp4"))
        val original = file.readBytes()

        // The disk fills after a few hundred bytes of the new moov.
        val result = process(file) { FailingFile(it, failAfterBytes = 300) }

        assertTrue(result.toString(), result is PostProcessResult.Failed)
        assertArrayEquals(original, file.readBytes())
        assertEquals(listOf("vide"), inspect(file).tracks.map { it.handler })
    }

    @Test
    fun aFailureAtTheSwitchKeepsTheOriginalMoovFirst() {
        val file = Mp4Fixture.write(dir.newFile("switch.mp4"))
        val original = file.readBytes()
        val (raf, f) = Mp4Fixture.open(file)
        val oldMoov = raf.use { BoxReader.children(f, 0, f.size()).single { it.type == "moov" } }

        val result = process(file) { FailingFile(it, failAtPosition = oldMoov.offset) }

        assertTrue(result.toString(), result is PostProcessResult.Failed)
        val bytes = file.readBytes()
        // Everything MediaMuxer wrote is untouched, so its moov is still the first one players find.
        assertArrayEquals(original, bytes.copyOf(original.size))
    }

    @Test
    fun anUnexpectedFileIsSkippedUntouched() {
        val file = dir.newFile("junk.mp4").apply { writeBytes(ByteArray(64) { 7 }) }
        val original = file.readBytes()

        val result = process(file)

        assertTrue(result.toString(), result is PostProcessResult.Skipped)
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun aTruncatedFileIsSkipped() {
        val file = Mp4Fixture.write(dir.newFile("cut.mp4"))
        val cut = file.readBytes().copyOf(file.length().toInt() - 10)
        file.writeBytes(cut)

        assertTrue(process(file) is PostProcessResult.Skipped)
        assertArrayEquals(cut, file.readBytes())
    }

    @Test
    fun aSecondRunIsSkipped() {
        val file = Mp4Fixture.write(dir.newFile("twice.mp4"))
        assertTrue(process(file) is PostProcessResult.Done)
        val once = file.readBytes()

        assertTrue(process(file) is PostProcessResult.Skipped)
        assertArrayEquals(once, file.readBytes())
    }

    @Test
    fun dropFrameTimecodeIsFlaggedInTheSampleDescription() {
        val file = Mp4Fixture.write(dir.newFile("df.mp4"))
        val df = TimecodeStart(frame = 107_892, numerator = 30_000, denominator = 1_001)

        assertTrue(process(file, PostProcessOptions(timecode = df)) is PostProcessResult.Done)

        val tc = inspect(file).timecode!!
        assertTrue(tc.dropFrame)
        assertEquals(30, tc.nominal)
        assertEquals("01:00:00;00", tc.label)
    }

    /** Throws like a full or unplugged drive after [failAfterBytes] written bytes, or on a write at [failAtPosition]. */
    private class FailingFile(private val inner: SeekableFile, private val failAfterBytes: Long = Long.MAX_VALUE, private val failAtPosition: Long = -1) :
        SeekableFile by inner {
        private var written = 0L

        override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) {
            if (position == failAtPosition) throw IOException("EIO at $position")
            if (written + length > failAfterBytes) {
                val part = (failAfterBytes - written).toInt().coerceAtLeast(0)
                inner.write(position, buffer, offset, part)
                written += part
                throw IOException("ENOSPC")
            }
            inner.write(position, buffer, offset, length)
            written += length
        }
    }
}
