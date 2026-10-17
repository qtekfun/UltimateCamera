package com.qtekfun.ultimatecamera.core.storage

import java.io.IOException
import java.util.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Bytes written in a time, from [WriteSpeedTest]. Megabytes are decimal (10^6 bytes), as drive makers quote them.
 */
data class WriteSpeedResult(val bytes: Long, val elapsedNanos: Long) {
    init {
        require(bytes >= 0 && elapsedNanos > 0) { "invalid measurement" }
    }

    val bytesPerSecond: Double get() = bytes * NANOS_PER_SECOND / elapsedNanos
    val megabytesPerSecond: Double get() = bytesPerSecond / BYTES_PER_MEGABYTE
    val bitsPerSecond: Double get() = bytesPerSecond * Byte.SIZE_BITS

    private companion object {
        const val NANOS_PER_SECOND = 1e9
        const val BYTES_PER_MEGABYTE = 1e6
    }
}

/** Progress of a running [WriteSpeedTest], 0..1, from the test's thread (primitive: no boxing per buffer). */
fun interface SpeedTestProgress {
    fun onProgress(fraction: Float)
}

/** Outcome of [WriteSpeedTest.run] on a location. */
sealed interface SpeedTestOutcome {
    data class Measured(val result: WriteSpeedResult) : SpeedTestOutcome

    /** Too little free space for a meaningful test without filling the drive. */
    data class NotEnoughSpace(val freeBytes: Long) : SpeedTestOutcome

    /** An I/O error; [reason] is English diagnostic text (D15). */
    data class Failed(val reason: String) : SpeedTestOutcome
}

/**
 * Measures sustained sequential write throughput (REQ-STO-001) by streaming one file until [Config.maxBytes] or
 * [Config.maxDurationNanos], whichever comes first, from one reused [Config.bufferBytes] buffer. It calls `fsync`
 * every [Config.syncEveryBytes] and at the end, inside the timed span: the page cache cannot inflate the result and
 * dirty data in RAM stays bounded (owner rule: disk, not RAM). Cancelling the coroutine stops it within one buffer.
 *
 * On a location it runs on [dispatcher] (use [AndroidStorageBackend.lowPriorityDispatcher]) and always deletes its
 * file, also after an error or a cancellation.
 */
class WriteSpeedTest(private val config: Config = Config(), private val nanoTime: () -> Long = System::nanoTime) {
    /**
     * @property maxBytes upper bound of the test file; also capped to [freeFraction] of the free space.
     * @property minBytes below this free-space-derived size the test is refused ([SpeedTestOutcome.NotEnoughSpace]).
     */
    data class Config(
        val maxBytes: Long = 512L * MIB,
        val minBytes: Long = 32L * MIB,
        val maxDurationNanos: Long = 5_000_000_000L,
        val bufferBytes: Int = 1 * MIB,
        val syncEveryBytes: Long = 32L * MIB,
        val freeFraction: Double = 0.25
    ) {
        init {
            require(bufferBytes > 0 && minBytes in 1..maxBytes && syncEveryBytes > 0 && maxDurationNanos > 0) { "invalid config" }
            require(freeFraction > 0 && freeFraction <= 1) { "freeFraction must be in (0, 1]" }
        }
    }

    /** How many bytes to write given the free space (null: unknown), or null if there is too little room. */
    fun plannedBytes(freeBytes: Long?): Long? {
        val cap = freeBytes?.let { (it * config.freeFraction).toLong() } ?: config.maxBytes
        val planned = minOf(config.maxBytes, cap)
        return planned.takeIf { it >= config.minBytes }
    }

    /** Streams up to [maxBytes] into [sink]; the elapsed time includes the final sync. */
    suspend fun measure(sink: WriteSink, maxBytes: Long = config.maxBytes, progress: SpeedTestProgress? = null): WriteSpeedResult {
        val buffer = ByteArray(config.bufferBytes).also { Random(BUFFER_SEED).nextBytes(it) } // incompressible
        var written = 0L
        var sinceSync = 0L
        val start = nanoTime()
        while (written < maxBytes && nanoTime() - start < config.maxDurationNanos) {
            currentCoroutineContext().ensureActive()
            val length = minOf(buffer.size.toLong(), maxBytes - written).toInt()
            sink.write(buffer, 0, length)
            written += length
            sinceSync += length
            if (sinceSync >= config.syncEveryBytes) {
                sink.sync()
                sinceSync = 0
            }
            progress?.onProgress(maxOf(written.toFloat() / maxBytes, (nanoTime() - start).toFloat() / config.maxDurationNanos).coerceAtMost(1f))
        }
        sink.sync()
        return WriteSpeedResult(written, (nanoTime() - start).coerceAtLeast(1))
    }

    /** Runs the test on [location] through [backend]. Throws only [kotlinx.coroutines.CancellationException]. */
    suspend fun run(
        backend: StorageBackend,
        location: RecordingLocation,
        dispatcher: CoroutineDispatcher,
        progress: SpeedTestProgress? = null
    ): SpeedTestOutcome = withContext(dispatcher) {
        val free = backend.freeBytes(location)
        val planned = plannedBytes(free) ?: return@withContext SpeedTestOutcome.NotEnoughSpace(free ?: 0)
        var file: OutputFile? = null
        try {
            backend.deleteIfExists(location, FILE_NAME)
            file = backend.createFile(location, FILE_NAME, MIME_TYPE, temporary = true)
            SpeedTestOutcome.Measured(measure(file, planned, progress))
        } catch (e: IOException) {
            SpeedTestOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) { deleteQuietly(file) }
        }
    }

    private fun deleteQuietly(file: OutputFile?) {
        try {
            file?.delete()
        } catch (_: IOException) {
            // Left behind: the next run deletes it by name, and MediaStore drops pending files by itself.
        }
    }

    companion object {
        const val MIB = 1024 * 1024
        const val FILE_NAME = ".ultimatecamera-speedtest.mp4"

        /** Video MIME type because the default location (MediaStore `Movies/`) only takes video. */
        const val MIME_TYPE = "video/mp4"
        private const val BUFFER_SEED = 0x55C0FFEEL
    }
}
