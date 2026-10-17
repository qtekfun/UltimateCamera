package com.qtekfun.ultimatecamera.core.recording

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
}

/**
 * A take's MP4 created through core-storage, in the user's folder or the default MediaStore location (D27). The file is
 * visible from the first byte, never pending: if the app dies mid-take MediaStore must not delete the footage, and the
 * MP4 of an interrupted take can still be repaired.
 */
class StorageVideoOutput private constructor(private val file: OutputFile, override val displayName: String) : VideoOutput {
    override val fileDescriptor: FileDescriptor get() = file.fileDescriptor

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
        fun create(backend: StorageBackend, location: RecordingLocation, cameraId: String, now: LocalDateTime = LocalDateTime.now()): StorageVideoOutput {
            val names: ClipNames = OutputNaming.next(now, cameraId, firstTake = 1, existingNames = backend.listFileNames(location))
            return StorageVideoOutput(backend.createFile(location, names.video, MIME_TYPE), names.video)
        }
    }
}
