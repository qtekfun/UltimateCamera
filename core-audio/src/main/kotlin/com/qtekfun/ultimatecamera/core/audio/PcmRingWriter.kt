package com.qtekfun.ultimatecamera.core.audio

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Moves PCM from the audio thread to a slow [target] (the separate WAV on an SD card or a USB drive, D45 leftover)
 * through a bounded single-producer/single-consumer ring and a dedicated writer thread.
 *
 * The producer side ([write], [padSilence]) never blocks, takes no lock and does not allocate: a buffer is copied
 * into the ring whole, or, when the ring has no room for it (the drive stalled for longer than the ring holds), it is
 * dropped whole and counted in [droppedSamples]. Every dropped sample is written as silence at the place it belonged,
 * so the file keeps its exact sample count and nothing after an overrun moves relative to the video: what is lost is
 * the content of the stall, never the sync.
 *
 * Exactly one thread may produce at a time (the audio thread; a new one after the previous one has been joined).
 * [close] is called once the producer has stopped: it drains everything, writes the silence still owed and closes
 * [closeTarget] on the writer thread, then returns. A [target] that throws [IOException] stops the writer; the error
 * is in [error] and later buffers are ignored.
 */
class PcmRingWriter(
    private val target: PcmSink,
    private val closeTarget: AutoCloseable?,
    private val channels: Int,
    capacitySamples: Int,
    private val gapSlots: Int = DEFAULT_GAP_SLOTS,
    chunkSamples: Int = DEFAULT_CHUNK_SAMPLES,
    private val closeTimeoutMs: Long = DEFAULT_CLOSE_TIMEOUT_MS,
    threadName: String = "uc-wav-writer"
) : PcmSink,
    AutoCloseable {

    init {
        require(channels > 0) { "channels $channels" }
        require(capacitySamples >= channels && gapSlots > 0 && chunkSamples >= channels) { "capacity $capacitySamples, gaps $gapSlots" }
    }

    /** Whole frames only, so a read that wraps at the end of the ring never splits a frame. */
    private val capacity = capacitySamples - capacitySamples % channels
    private val chunk = chunkSamples - chunkSamples % channels
    private val ring = ShortArray(capacity)
    private val zeros = ShortArray(chunk)

    /** Samples ever published by the producer / consumed by the writer; their difference is the fill. */
    private val writePos = AtomicLong()
    private val readPos = AtomicLong()

    /** Silence owed at a ring position: published before the data that follows it. */
    private val gapAt = LongArray(gapSlots)
    private val gapLength = LongArray(gapSlots)
    private val gapWrite = AtomicLong()
    private val gapRead = AtomicLong()

    /** Silence (samples) owed but not yet placed in the stream; producer-owned until [close]. */
    @Volatile private var owed = 0L
    private var overrunning = false

    @Volatile private var closing = false

    /** Samples the producer could not queue (written as silence instead). */
    @Volatile var droppedSamples = 0L
        private set

    /** Separate overrun episodes (one or more dropped buffers in a row). */
    @Volatile var overruns = 0
        private set

    /** The fullest the ring has been, in samples. */
    @Volatile var peakFillSamples = 0L
        private set

    /** Samples handed to [target], silence included. */
    @Volatile var writtenSamples = 0L
        private set

    /** Why the writer stopped (the drive failed or filled up), or null. */
    @Volatile var error: String? = null
        private set

    private val thread = Thread(::run, threadName).apply {
        isDaemon = true
        start()
    }

    /** Queues [count] samples (whole frames) of [samples] from [offset]. Audio thread: never blocks or allocates. */
    override fun write(samples: ShortArray, offset: Int, count: Int) {
        if (count <= 0 || closing || error != null) return
        val w = writePos.get()
        val fill = w - readPos.get()
        val debt = owed
        if (count > capacity - fill || (debt > 0 && gapWrite.get() - gapRead.get() >= gapSlots)) {
            drop(count)
            return
        }
        overrunning = false
        if (debt > 0) {
            val g = gapWrite.get()
            val slot = (g % gapSlots).toInt()
            gapAt[slot] = w
            gapLength[slot] = debt
            gapWrite.set(g + 1)
            owed = 0
        }
        val at = (w % capacity).toInt()
        val first = minOf(count, capacity - at)
        System.arraycopy(samples, offset, ring, at, first)
        if (first < count) System.arraycopy(samples, offset + first, ring, 0, count - first)
        writePos.set(w + count)
        if (fill + count > peakFillSamples) peakFillSamples = fill + count
        LockSupport.unpark(thread)
    }

    /**
     * Owes [frames] frames of silence before the next queued buffer (or at the end): audio that was never captured,
     * such as the microphone handover between two parts of a take, so the file stays on the video's timeline.
     * Producer side, like [write].
     */
    fun padSilence(frames: Long) {
        if (frames > 0 && !closing) owed += frames * channels
    }

    private fun drop(count: Int) {
        if (!overrunning) overruns++
        overrunning = true
        owed += count
        droppedSamples += count
        LockSupport.unpark(thread)
    }

    /** Drains the ring and the silence owed, closes [closeTarget] and waits for the writer (bounded). */
    override fun close() {
        if (closing) return
        closing = true
        LockSupport.unpark(thread)
        thread.join(closeTimeoutMs)
        if (thread.isAlive) error = error ?: "writer still busy after $closeTimeoutMs ms"
    }

    private fun run() {
        try {
            pump()
        } catch (e: IOException) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try {
                closeTarget?.close()
            } catch (e: IOException) {
                error = error ?: "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private fun pump() {
        while (true) {
            // Read before looking for work: once it is set the producer is done, so an empty ring is the end.
            val ending = closing
            if (!step()) {
                if (ending) {
                    writeSilence(owed)
                    owed = 0
                    return
                }
                LockSupport.parkNanos(this, IDLE_PARK_NANOS)
            }
        }
    }

    /** Writes one piece (a gap or a contiguous run of the ring); false when there was nothing to write. */
    private fun step(): Boolean {
        val r = readPos.get()
        // writePos before gapWrite: a gap is published before the data after it, so none can be missed.
        var limit = writePos.get()
        val g = gapRead.get()
        if (g < gapWrite.get()) {
            val slot = (g % gapSlots).toInt()
            if (gapAt[slot] == r) {
                writeSilence(gapLength[slot])
                gapRead.set(g + 1)
                return true
            }
            limit = minOf(limit, gapAt[slot])
        }
        if (r >= limit) return false
        val at = (r % capacity).toInt()
        val n = minOf(limit - r, chunk.toLong(), (capacity - at).toLong()).toInt()
        target.write(ring, at, n)
        writtenSamples += n
        readPos.set(r + n)
        return true
    }

    private fun writeSilence(samples: Long) {
        var left = samples
        while (left > 0) {
            val n = minOf(left, chunk.toLong()).toInt()
            target.write(zeros, 0, n)
            writtenSamples += n
            left -= n
        }
    }

    companion object {
        /** Up to this many separate gaps can wait in the ring; one more is dropped with the buffer after it. */
        const val DEFAULT_GAP_SLOTS = 64

        /** Largest single write to the target: 4096 samples is about 43 ms of 48 kHz stereo. */
        const val DEFAULT_CHUNK_SAMPLES = 4096

        const val DEFAULT_CLOSE_TIMEOUT_MS = 15_000L
        private const val IDLE_PARK_NANOS = 5_000_000L

        /** A ring of [seconds] of audio. */
        fun samplesFor(sampleRate: Int, channels: Int, seconds: Int): Int = sampleRate * channels * seconds
    }
}
