package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.audio.BextChunk
import com.qtekfun.ultimatecamera.core.audio.WavWriter
import com.qtekfun.ultimatecamera.core.storage.ClipNames
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import java.io.IOException

/**
 * The separate WAV of a take (REQ-AUD-003): 16-bit PCM of the processed audio (after gain and limiter, as in the AAC
 * track), named like the MP4 ([ClipNames.wav]). Like the MP4 it is never pending, and its header is patched about
 * once a second, so an interrupted take still leaves a playable WAV. With [bext] it is a Broadcast Wave file carrying the
 * take's start timecode (D58).
 */
class WavSidecar(private val file: OutputFile, config: AudioConfig, bext: BextChunk? = null) {
    /** The PCM tap for [AudioTrackRecorder]. */
    val writer = WavWriter(file.fileDescriptor, config.sampleRate, config.channels, bext = bext)

    /** Patches the header, then syncs and keeps the file, or deletes it. Blocks on I/O. */
    fun finish(keep: Boolean) {
        runCatching { writer.close() }
        if (keep) {
            runCatching { file.sync() }
            runCatching { file.close() }
        } else {
            runCatching { file.delete() }
        }
    }

    companion object {
        /**
         * WAV MIME type as Android's MIME map knows it: SAF and MediaStore append an extension when the type does not
         * match the name's (`audio/wav` could end as `.wav.wav`).
         */
        const val MIME_TYPE = "audio/x-wav"

        /** Creates [names]' WAV next to its MP4 (default location: `Recordings/UltimateCamera/`, D27). Blocks on I/O. */
        @Throws(IOException::class)
        fun create(backend: StorageBackend, location: RecordingLocation, names: ClipNames): OutputFile = backend.createFile(location, names.wav, MIME_TYPE)

        /** Deletes a WAV that will not be recorded (the take did not start). */
        fun discard(file: OutputFile?) {
            runCatching { file?.delete() }
        }
    }
}
