package com.qtekfun.ultimatecamera.core.recording

import android.system.ErrnoException
import android.system.Os
import com.qtekfun.ultimatecamera.core.storage.ClipNames
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import com.qtekfun.ultimatecamera.core.storage.OutputNaming
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import java.io.FileDescriptor
import java.io.IOException
import java.time.LocalDateTime

/** Where one take is written: an open file descriptor while recording, kept or discarded at the end. */
interface VideoOutput {
    val fileDescriptor: FileDescriptor
    val displayName: String

    /** Flushes and keeps the file (something usable was written) or deletes it. */
    fun finish(keep: Boolean)

    /** Bytes in the file so far (what the muxer has written), or -1 when unknown. */
    fun bytesWritten(): Long = -1

    /** Opens the next part of the same take (`…_T002.mp4` after `…_T001.mp4`, D47). Blocks on I/O. */
    @Throws(IOException::class)
    fun nextPart(): VideoOutput = throw IOException("this output cannot be split")
}

/**
 * A take's MP4 created through core-storage, in the user's folder or the default MediaStore location (D27). The file is
 * visible from the first byte, never pending: if the app dies mid-take MediaStore must not delete the footage, and the
 * MP4 of an interrupted take can still be repaired.
 */
class StorageVideoOutput private constructor(
    private val file: OutputFile,
    private val names: ClipNames,
    private val backend: StorageBackend,
    private val location: RecordingLocation,
    private val cameraId: String,
    private val time: LocalDateTime
) : VideoOutput {
    override val fileDescriptor: FileDescriptor get() = file.fileDescriptor
    override val displayName: String get() = names.video

    /** The take number in the name (1 for `…_T001`). */
    val take: Int get() = names.take

    override fun bytesWritten(): Long = try {
        Os.fstat(file.fileDescriptor).st_size
    } catch (_: ErrnoException) {
        -1
    }

    override fun nextPart(): VideoOutput = open(backend, location, cameraId, time, firstTake = names.take + 1)

    override fun finish(keep: Boolean) {
        if (keep) {
            // fsync before close: a take reported as saved must survive a power cut right after.
            runCatching { file.sync() }
            runCatching { file.close() }
        } else {
            runCatching { file.delete() }
        }
    }

    companion object {
        const val MIME_TYPE = "video/mp4"

        /** Creates the next free take name for [cameraId] in [location] and opens it. Blocks on I/O. */
        @Throws(IOException::class)
        fun create(backend: StorageBackend, location: RecordingLocation, cameraId: String, now: LocalDateTime = LocalDateTime.now()): StorageVideoOutput =
            open(backend, location, cameraId, now, firstTake = 1)

        private fun open(backend: StorageBackend, location: RecordingLocation, cameraId: String, time: LocalDateTime, firstTake: Int): StorageVideoOutput {
            val names: ClipNames = OutputNaming.next(time, cameraId, firstTake, existingNames = backend.listFileNames(location))
            return StorageVideoOutput(backend.createFile(location, names.video, MIME_TYPE), names, backend, location, cameraId, time)
        }
    }
}
