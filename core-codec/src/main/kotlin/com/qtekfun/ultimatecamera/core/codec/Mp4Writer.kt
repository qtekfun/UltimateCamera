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
class Mp4Writer private constructor(private val muxer: MediaMuxer, private val expectedTracks: Int) {
    constructor(file: File, expectedTracks: Int, orientationHint: Int = 0) :
        this(MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { it.setOrientationHint(orientationHint) }, expectedTracks)

    /** Writes to [fd] (a SAF or MediaStore file opened "rw"); the caller closes it after [stop]. */
    constructor(fd: FileDescriptor, expectedTracks: Int, orientationHint: Int = 0) :
        this(MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { it.setOrientationHint(orientationHint) }, expectedTracks)

    private val lock = Any()
    private var added = 0
    private var started = false
    private var stopped = false
    private val pending = ArrayDeque<Pending>()
    private var pendingBytes = 0L

    /** First error (muxer refused a sample, pending queue overflowed); the file may be unusable when set. */
    @Volatile var error: String? = null
        private set

    /** A track of this file, handed to one encoder. */
    inner class Track internal constructor(private val index: Int) {
        fun write(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = writeSample(index, buffer, info)
    }

    /** Registers an encoder's output format; call from its onOutputFormatChanged. */
    fun addTrack(format: MediaFormat): Track = synchronized(lock) {
        check(!started && !stopped) { "tracks must be added before the muxer starts" }
        val index = muxer.addTrack(format)
        added++
        if (added == expectedTracks) start()
        Track(index)
    }

    private fun start() {
        muxer.start()
        started = true
        while (pending.isNotEmpty()) {
            val p = pending.removeFirst()
            write(p.track, p.data, p.info)
        }
        pendingBytes = 0
    }

    private fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (stopped) return
        if (started) {
            write(track, buffer, info)
            return
        }
        if (pendingBytes + info.size > MAX_PENDING_BYTES) {
            error = error ?: "muxer did not start: a track never announced its format"
            return
        }
        val copy = ByteBuffer.allocateDirect(info.size)
        buffer.position(info.offset).limit(info.offset + info.size)
        copy.put(buffer).flip()
        pending += Pending(track, copy, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
        pendingBytes += info.size
    }

    private fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        runCatching { muxer.writeSampleData(track, buffer, info) }.onFailure { error = error ?: "writeSampleData: ${it.message}" }
    }

    /** Finishes the file. Safe to call once every encoder has stopped; later samples are ignored. */
    fun stop() = synchronized(lock) {
        if (stopped) return
        stopped = true
        if (started) runCatching { muxer.stop() }.onFailure { error = error ?: "muxer.stop: ${it.message}" }
        muxer.release()
    }

    /** True once the muxer started, i.e. the file has at least the track headers. */
    val isStarted: Boolean get() = synchronized(lock) { started }

    private class Pending(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private companion object {
        /** About two seconds of 4K60 video; a track that is not ready by then is a bug, not a slow start. */
        const val MAX_PENDING_BYTES = 32L * 1024 * 1024
    }
}
