package com.qtekfun.ultimatecamera.core.audio

import com.qtekfun.ultimatecamera.core.audio.dsp.Signals
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmRingWriterTest {
    @get:Rule val temp = TemporaryFolder()

    /** Records every sample it gets; can be stalled (a drive that stops answering) and slowed down. */
    private class FakeDrive(private val delayEveryNth: Int = 0, private val delayMs: Long = 0) :
        PcmSink,
        AutoCloseable {
        @Volatile private var stalled = false
        private val out = ArrayList<Short>()
        private val calls = AtomicInteger()

        @Volatile var closed = false

        fun stall(): FakeDrive = apply { stalled = true }

        fun open(): FakeDrive = apply { stalled = false }

        val samples: ShortArray get() = synchronized(out) { out.toShortArray() }

        override fun write(samples: ShortArray, offset: Int, count: Int) {
            while (stalled) Thread.sleep(1)
            if (delayEveryNth > 0 && calls.incrementAndGet() % delayEveryNth == 0) Thread.sleep(delayMs)
            synchronized(out) { for (i in 0 until count) out += samples[offset + i] }
        }

        override fun close() {
            closed = true
        }
    }

    private fun buffer(index: Int, size: Int = BUFFER): ShortArray = ShortArray(size) { (index * 7 % 30_000 + 1).toShort() }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            check(System.nanoTime() < until) { "timed out" }
            Thread.sleep(1)
        }
    }

    @Test(timeout = 10_000)
    fun aStalledDriveNeverBlocksTheAudioThread() {
        // A synchronous writer would hang here forever: the drive does not answer until the end.
        val drive = FakeDrive().stall()
        val ring = PcmRingWriter(drive, drive, channels = 2, capacitySamples = 4 * BUFFER)
        val started = System.nanoTime()
        repeat(500) { ring.write(buffer(it), 0, BUFFER) }
        assertTrue("500 writes took too long", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
        drive.open()
        ring.close()
        assertTrue(drive.closed)
        assertEquals(500L * BUFFER, drive.samples.size.toLong())
    }

    @Test(timeout = 10_000)
    fun droppedBuffersBecomeSilenceInTheirPlaceSoNothingShifts() {
        val drive = FakeDrive().stall()
        val ring = PcmRingWriter(drive, drive, channels = 2, capacitySamples = 4 * BUFFER)
        repeat(10) { ring.write(buffer(it), 0, BUFFER) } // 4 fit, 6 are dropped.
        assertEquals(6L * BUFFER, ring.droppedSamples)
        assertEquals(1, ring.overruns)
        drive.open()
        waitFor { ring.writtenSamples == 4L * BUFFER }
        repeat(2) { ring.write(buffer(10 + it), 0, BUFFER) }
        ring.close()
        val expected = (0 until 12).flatMap { if (it in 4..9) ShortArray(BUFFER).toList() else buffer(it).toList() }
        assertArrayEquals(expected.toShortArray(), drive.samples)
        assertEquals(12L * BUFFER, ring.writtenSamples)
        assertEquals(4L * BUFFER, ring.peakFillSamples)
        assertNull(ring.error)
    }

    @Test(timeout = 10_000)
    fun separateStallsAreCountedSeparately() {
        val drive = FakeDrive().stall()
        val ring = PcmRingWriter(drive, drive, channels = 1, capacitySamples = 2 * BUFFER)
        repeat(3) { ring.write(buffer(it), 0, BUFFER) } // Third dropped.
        assertEquals(1, ring.overruns)
        drive.open()
        waitFor { ring.writtenSamples == 2L * BUFFER }
        drive.stall()
        repeat(3) { ring.write(buffer(3 + it), 0, BUFFER) } // First places the gap; third dropped again.
        assertEquals(2, ring.overruns)
        drive.open()
        ring.close()
        assertEquals(2L * BUFFER, ring.droppedSamples)
        val expected = (0 until 6).flatMap { if (it == 2 || it == 5) ShortArray(BUFFER).toList() else buffer(it).toList() }
        assertArrayEquals(expected.toShortArray(), drive.samples)
    }

    @Test(timeout = 10_000)
    fun silenceStillOwedAtStopIsWrittenSoTheWavHasTheExactLength() {
        val file = temp.newFile("take.wav")
        val wav = WavWriter(file, 48_000, 2)
        val gate = CountDownLatch(1)
        val stalled = PcmSink { s, o, c ->
            gate.await()
            wav.write(s, o, c)
        }
        val ring = PcmRingWriter(stalled, wav, channels = 2, capacitySamples = 3 * BUFFER)
        repeat(8) { ring.write(buffer(it), 0, BUFFER) } // The last 5 never fit: the take ends during the stall.
        gate.countDown()
        ring.close()
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8 * BUFFER * 2, header.getInt(40))
        assertEquals(36 + 8 * BUFFER * 2, header.getInt(4))
        assertEquals(WavWriter.HEADER_SIZE + 8 * BUFFER * 2, bytes.size)
        assertEquals(buffer(2)[0], header.getShort(WavWriter.HEADER_SIZE + 2 * 2 * BUFFER))
        assertEquals(0.toShort(), header.getShort(WavWriter.HEADER_SIZE + 3 * 2 * BUFFER))
        assertEquals(0.toShort(), header.getShort(bytes.size - 2))
    }

    @Test(timeout = 10_000)
    fun paddedSilenceGoesExactlyWhereItWasOwed() {
        val drive = FakeDrive().open()
        val ring = PcmRingWriter(drive, drive, channels = 2, capacitySamples = 8 * BUFFER)
        ring.write(buffer(0), 0, BUFFER)
        ring.padSilence(frames = 30)
        ring.write(buffer(1), 0, BUFFER)
        ring.padSilence(frames = 5) // At the end of the take.
        ring.close()
        val expected = buffer(0).toList() + ShortArray(60).toList() + buffer(1).toList() + ShortArray(10).toList()
        assertArrayEquals(expected.toShortArray(), drive.samples)
        assertEquals(0L, ring.droppedSamples)
    }

    @Test(timeout = 10_000)
    fun aFullGapTableDropsTheNextBufferInsteadOfLosingAGap() {
        val drive = FakeDrive().stall()
        val ring = PcmRingWriter(drive, drive, channels = 1, capacitySamples = 4 * SMALL, gapSlots = 1)
        ring.write(buffer(0, SMALL), 0, SMALL)
        ring.padSilence(1)
        ring.write(buffer(1, SMALL), 0, SMALL) // Uses the only gap slot.
        ring.padSilence(1)
        ring.write(buffer(2, SMALL), 0, SMALL) // No slot for the second gap: dropped, owed as silence.
        assertEquals(SMALL.toLong(), ring.droppedSamples)
        drive.open()
        ring.close()
        val expected = buffer(0, SMALL).toList() + ShortArray(1).toList() + buffer(1, SMALL).toList() + ShortArray(1 + SMALL).toList()
        assertArrayEquals(expected.toShortArray(), drive.samples)
    }

    @Test(timeout = 30_000)
    fun stressSlowDriveKeepsOrderAndExactLength() {
        val drive = FakeDrive(delayEveryNth = 7, delayMs = 3).open()
        val ring = PcmRingWriter(drive, drive, channels = 2, capacitySamples = 6 * BUFFER, chunkSamples = 1000)
        val total = 2_000
        val producer = Thread {
            for (i in 0 until total) {
                ring.write(buffer(i), 0, BUFFER)
                if (i % 50 == 0) Thread.sleep(1)
            }
        }
        producer.start()
        producer.join()
        ring.close()
        val out = drive.samples
        assertEquals(total.toLong() * BUFFER, out.size.toLong())
        var silent = 0L
        for (i in 0 until total) {
            val first = out[i * BUFFER]
            val expected = buffer(i)[0]
            assertTrue("buffer $i out of place", first == expected || first == 0.toShort())
            for (k in 0 until BUFFER) assertEquals("buffer $i sample $k", first, out[i * BUFFER + k])
            if (first == 0.toShort()) silent += BUFFER
        }
        assertEquals(ring.droppedSamples, silent)
        assertTrue("the slow drive should have overrun", ring.droppedSamples > 0)
        assertTrue(ring.overruns > 0)
    }

    @Test
    fun theAudioThreadDoesNotAllocateWhenQueueingOrDropping() {
        val idle = PcmSink { _, _, _ -> }
        val ring = PcmRingWriter(idle, null, channels = 2, capacitySamples = 64 * BUFFER)
        val pcm = buffer(1)
        assertEquals(0L, Signals.allocatedBytes { repeat(20) { ring.write(pcm, 0, BUFFER) } })
        ring.close()
        val stalled = FakeDrive().stall()
        val full = PcmRingWriter(stalled, stalled, channels = 2, capacitySamples = BUFFER)
        full.write(pcm, 0, BUFFER)
        assertEquals(0L, Signals.allocatedBytes { repeat(20) { full.write(pcm, 0, BUFFER) } })
        stalled.open()
        full.close()
    }

    @Test(timeout = 10_000)
    fun aFailingDriveStopsTheWriterAndStillClosesIt() {
        var closed = false
        val failing = PcmSink { _, _, _ -> throw IOException("No space left on device") }
        val ring = PcmRingWriter(failing, { closed = true }, channels = 1, capacitySamples = 4 * SMALL)
        ring.write(buffer(0, SMALL), 0, SMALL)
        waitFor { ring.error != null }
        ring.write(buffer(1, SMALL), 0, SMALL) // Ignored.
        ring.close()
        ring.close() // Twice is harmless.
        assertNotNull(ring.error)
        assertTrue(ring.error!!.contains("No space"))
        assertTrue(closed)
        assertEquals(0L, ring.droppedSamples)
    }

    @Test(timeout = 10_000)
    fun aDriveThatNeverAnswersEndsCloseAfterTheTimeout() {
        val drive = FakeDrive().stall()
        val ring = PcmRingWriter(drive, drive, channels = 1, capacitySamples = 4 * SMALL, closeTimeoutMs = 50)
        ring.write(buffer(0, SMALL), 0, SMALL)
        ring.close()
        assertNotNull(ring.error)
        ring.write(buffer(1, SMALL), 0, SMALL) // After close: ignored.
        drive.open()
    }

    @Test
    fun ringSizeIsWholeSeconds() {
        assertEquals(48_000 * 2 * 4, PcmRingWriter.samplesFor(48_000, 2, 4))
    }

    private companion object {
        /** 10 ms of 48 kHz stereo. */
        const val BUFFER = 960
        const val SMALL = 4
    }
}
