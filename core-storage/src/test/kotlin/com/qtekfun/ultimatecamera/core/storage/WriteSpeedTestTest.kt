package com.qtekfun.ultimatecamera.core.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteSpeedTestTest {
    private val mib = WriteSpeedTest.MIB.toLong()

    /** A sink that "takes" [nanosPerMib] of a fake clock per MiB written, and [syncNanos] per sync. */
    private class TimedSink(private val clock: LongArray, private val nanosPerMib: Long, private val syncNanos: Long = 0) : WriteSink {
        var bytes = 0L
        var syncs = 0
        val bufferIdentities = mutableSetOf<Int>()

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bufferIdentities += System.identityHashCode(buffer)
            bytes += length
            clock[0] += nanosPerMib * length / WriteSpeedTest.MIB
        }

        override fun sync() {
            syncs++
            clock[0] += syncNanos
        }
    }

    @Test
    fun stopsAtTheByteCapAndCountsTheFinalSync() = runTest {
        val clock = longArrayOf(0)
        val test = WriteSpeedTest(WriteSpeedTest.Config(maxBytes = 64 * mib, syncEveryBytes = 16 * mib)) { clock[0] }
        // 10 ms per MiB = 104.9 MB/s while writing, plus 100 ms for each of the 5 syncs (4 periodic + final).
        val sink = TimedSink(clock, nanosPerMib = 10_000_000, syncNanos = 100_000_000)
        val result = test.measure(sink, 64 * mib)

        assertEquals(64 * mib, result.bytes)
        assertEquals(5, sink.syncs)
        assertEquals(640_000_000L + 500_000_000L, result.elapsedNanos)
        assertEquals(64 * mib * 1e9 / 1.14e9 / 1e6, result.megabytesPerSecond, 1e-9)
        assertEquals("one reused buffer", 1, sink.bufferIdentities.size)
    }

    @Test
    fun stopsAtTheTimeBoxOnASlowDrive() = runTest {
        val clock = longArrayOf(0)
        val test = WriteSpeedTest(WriteSpeedTest.Config(maxDurationNanos = 5_000_000_000)) { clock[0] }
        val sink = TimedSink(clock, nanosPerMib = 100_000_000) // ~10.5 MB/s
        val result = test.measure(sink)
        assertEquals(50 * mib, result.bytes)
        assertEquals(10.49, result.megabytesPerSecond, 0.01)
    }

    @Test
    fun plannedSizeRespectsFreeSpace() {
        val test = WriteSpeedTest()
        assertEquals(512 * mib, test.plannedBytes(null))
        assertEquals(512 * mib, test.plannedBytes(100L * 1024 * mib))
        assertEquals(100 * mib, test.plannedBytes(400 * mib))
        assertNull(test.plannedBytes(100 * mib))
    }

    @Test
    fun runDeletesItsFileAndReportsTheResult() = runTest {
        val backend = FakeStorageBackend()
        backend.files[RecordingLocation.Default] = mutableMapOf(WriteSpeedTest.FILE_NAME to 7L) // left by a crash
        val outcome = WriteSpeedTest(
            WriteSpeedTest.Config(maxBytes = 64 * mib)
        ).run(backend, RecordingLocation.Default, UnconfinedTestDispatcher(testScheduler))
        assertTrue(outcome is SpeedTestOutcome.Measured)
        assertEquals(64 * mib, (outcome as SpeedTestOutcome.Measured).result.bytes)
        assertTrue(backend.listFileNames(RecordingLocation.Default).isEmpty())
    }

    @Test
    fun runReportsErrorsAndLowSpace() = runTest {
        val backend = FakeStorageBackend()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backend.failWriteAfterBytes = 8 * mib
        val failed = WriteSpeedTest().run(backend, RecordingLocation.Default, dispatcher)
        assertEquals(SpeedTestOutcome.Failed("disk removed"), failed)
        assertTrue("file deleted after the error", backend.listFileNames(RecordingLocation.Default).isEmpty())

        backend.free[RecordingLocation.Default] = 10 * mib
        assertEquals(SpeedTestOutcome.NotEnoughSpace(10 * mib), WriteSpeedTest().run(backend, RecordingLocation.Default, dispatcher))
    }

    @Test
    fun cancellationStopsTheTestAndDeletesTheFile() = runTest {
        val backend = FakeStorageBackend()
        val clock = longArrayOf(0)
        var writes = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        var thrown: Throwable? = null
        val job = launch(dispatcher) {
            try {
                WriteSpeedTest { clock[0] }.run(
                    object : StorageBackend by backend {
                        override fun createFile(location: RecordingLocation, displayName: String, mimeType: String, temporary: Boolean): OutputFile {
                            val file = backend.createFile(location, displayName, mimeType, temporary)
                            return object : OutputFile by file {
                                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                    file.write(buffer, offset, length)
                                    if (++writes == 3) throw CancellationException("cancelled by the user")
                                }
                            }
                        }
                    },
                    RecordingLocation.Default,
                    dispatcher
                )
            } catch (e: CancellationException) {
                thrown = e
            }
        }
        job.join()
        assertTrue(thrown is CancellationException)
        assertEquals(3, writes)
        assertTrue(backend.listFileNames(RecordingLocation.Default).isEmpty())
    }

    @Test
    fun verdictThresholds() {
        val streams = RecordingStreams.UHD_60_10BIT
        assertEquals(127_560_000L, streams.totalBitsPerSecond)
        val needed = streams.totalBitsPerSecond.toDouble()
        assertEquals(SpeedVerdict.OK, WriteSpeedPolicy.classify(needed * 2.0, streams))
        assertEquals(SpeedVerdict.MARGINAL, WriteSpeedPolicy.classify(needed * 1.99, streams))
        assertEquals(SpeedVerdict.MARGINAL, WriteSpeedPolicy.classify(needed * 1.25, streams))
        assertEquals(SpeedVerdict.INSUFFICIENT, WriteSpeedPolicy.classify(needed * 1.24, streams))
        // A USB 2.0 flash stick (~20 MB/s) is marginal at best; a USB 3 SSD (~400 MB/s) is fine.
        assertEquals(SpeedVerdict.INSUFFICIENT, WriteSpeedPolicy.classify(WriteSpeedResult(19_000_000, 1_000_000_000), streams))
        assertEquals(SpeedVerdict.OK, WriteSpeedPolicy.classify(WriteSpeedResult(400_000_000, 1_000_000_000), streams))
        assertEquals(31.89, WriteSpeedPolicy.requiredBitsPerSecond(streams) / 8 / 1e6, 0.01)
    }

    @Test
    fun wavBitrate() {
        assertEquals(2_304_000L, RecordingStreams.wavBitsPerSecond(48_000, 24, 2))
        assertEquals(1_536_000L, RecordingStreams.wavBitsPerSecond(48_000, 16, 2))
    }
}
