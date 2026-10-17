package com.qtekfun.ultimatecamera.core.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * One MP4 file shared by several encoders (video and audio). The muxer starts once all [expectedTracks] have
 * announced their format; samples that arrive earlier (the video encoder is usually ready before the audio one) are
 * copied once into a small pending queue and written in order at start, so the first key frame is never lost.
 *
 * All methods are thread-safe: each encoder writes from its own thread.
 */
class Mp4Writer private constructor(muxer: MediaMuxer, expectedTracks: Int) {
    constructor(file: File, expectedTracks: Int, orientationHint: Int = 0) :
        this(MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { it.setOrientationHint(orientationHint) }, expectedTracks)

    /** Writes to [fd] (a SAF or MediaStore file opened "rw"); the caller closes it after [stop]. */
    constructor(fd: FileDescriptor, expectedTracks: Int, orientationHint: Int = 0) :
        this(MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { it.setOrientationHint(orientationHint) }, expectedTracks)

    private val core = TrackMuxer(MediaMuxerSink(muxer), expectedTracks)

    /** First error (muxer refused a sample, pending queue overflowed); the file may be unusable when set. */
    val error: String? get() = core.error

    /** A track of this file, handed to one encoder. */
    inner class Track internal constructor(private val index: Int) {
        fun write(buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
            core.writeSample(index, buffer, info.offset, info.size, info.presentationTimeUs, info.flags)
    }

    /** Registers an encoder's output format; call from its onOutputFormatChanged. */
    fun addTrack(format: MediaFormat): Track = Track(core.addTrack(format))

    /** Finishes the file. Safe to call once every encoder has stopped; later samples are ignored. */
    fun stop() = core.stop()

    /** True once the muxer started, i.e. the file has at least the track headers. */
    val isStarted: Boolean get() = core.isStarted
}

/** The muxer calls [TrackMuxer] makes: a `MediaMuxer` in the app, a fake in tests. [F] is the track format type. */
internal interface MuxerSink<F> {
    fun addTrack(format: F): Int

    fun start()

    @Suppress("LongParameterList")
    fun writeSample(track: Int, buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)

    fun stop()

    fun release()
}

/** [MuxerSink] on a `MediaMuxer`; one reused `BufferInfo`, since [TrackMuxer] writes under its lock. */
private class MediaMuxerSink(private val muxer: MediaMuxer) : MuxerSink<MediaFormat> {
    private val info = MediaCodec.BufferInfo()

    override fun addTrack(format: MediaFormat): Int = muxer.addTrack(format)

    override fun start() = muxer.start()

    override fun writeSample(track: Int, buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        info.set(offset, size, presentationTimeUs, flags)
        muxer.writeSampleData(track, buffer, info)
    }

    override fun stop() = muxer.stop()

    override fun release() = muxer.release()
}

/**
 * The logic of [Mp4Writer]: start once [expectedTracks] tracks are added, queue earlier samples (copied, at most
 * [maxPendingBytes]) and write them in order at start, ignore samples after [stop], keep the first error.
 */
internal class TrackMuxer<F>(private val sink: MuxerSink<F>, private val expectedTracks: Int, private val maxPendingBytes: Long = MAX_PENDING_BYTES) {
    private val lock = Any()
    private var added = 0
    private var started = false
    private var stopped = false
    private val pending = ArrayDeque<Pending>()
    private var pendingBytes = 0L

    @Volatile var error: String? = null
        private set

    fun addTrack(format: F): Int = synchronized(lock) {
        check(!started && !stopped) { "tracks must be added before the muxer starts" }
        val index = sink.addTrack(format)
        added++
        if (added == expectedTracks) start()
        index
    }

    private fun start() {
        sink.start()
        started = true
        while (pending.isNotEmpty()) {
            val p = pending.removeFirst()
            write(p.track, p.data, 0, p.size, p.presentationTimeUs, p.flags)
        }
        pendingBytes = 0
    }

    @Suppress("LongParameterList")
    fun writeSample(track: Int, buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) = synchronized(lock) {
        if (stopped) return
        if (started) {
            write(track, buffer, offset, size, presentationTimeUs, flags)
            return
        }
        if (pendingBytes + size > maxPendingBytes) {
            error = error ?: "muxer did not start: a track never announced its format"
            return
        }
        val copy = ByteBuffer.allocateDirect(size)
        buffer.position(offset).limit(offset + size)
        copy.put(buffer).flip()
        pending += Pending(track, copy, size, presentationTimeUs, flags)
        pendingBytes += size
    }

    @Suppress("LongParameterList")
    private fun write(track: Int, buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        runCatching { sink.writeSample(track, buffer, offset, size, presentationTimeUs, flags) }
            .onFailure { error = error ?: "writeSampleData: ${it.message}" }
    }

    fun stop() = synchronized(lock) {
        if (stopped) return
        stopped = true
        if (started) runCatching { sink.stop() }.onFailure { error = error ?: "muxer.stop: ${it.message}" }
        sink.release()
    }

    val isStarted: Boolean get() = synchronized(lock) { started }

    private class Pending(val track: Int, val data: ByteBuffer, val size: Int, val presentationTimeUs: Long, val flags: Int)

    companion object {
        /** About two seconds of 4K60 video; a track that is not ready by then is a bug, not a slow start. */
        const val MAX_PENDING_BYTES = 32L * 1024 * 1024
    }
}
