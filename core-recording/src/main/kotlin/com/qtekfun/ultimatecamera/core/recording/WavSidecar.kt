package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.audio.BextChunk
import com.qtekfun.ultimatecamera.core.audio.PcmRingWriter
import com.qtekfun.ultimatecamera.core.audio.PcmSink
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
 *
 * The audio thread never touches the drive (D86): [writer] queues into a [PcmRingWriter] of [bufferSeconds] and its
 * own thread writes the file. When a slow card or USB drive falls further behind than that, the buffers that do not
 * fit become silence of the same length, so the WAV keeps its sample count and its sync with the video; the loss is
 * counted and reported by [finish].
 */
class WavSidecar(private val file: OutputFile, private val config: AudioConfig, bext: BextChunk? = null, bufferSeconds: Int = BUFFER_SECONDS) {
    private val wav = WavWriter(file.fileDescriptor, config.sampleRate, config.channels, bext = bext)
    private val ring = PcmRingWriter(wav, wav, config.channels, PcmRingWriter.samplesFor(config.sampleRate, config.channels, bufferSeconds))
    private var errorReported = false

    /** The PCM tap for [AudioTrackRecorder]: never blocks or allocates. */
    val writer: PcmSink get() = ring

    /** Frames dropped so far because the drive was too slow (each written as silence). */
    val droppedFrames: Long get() = ring.droppedSamples / config.channels

    /**
     * Owes [frames] frames of silence (audio that was not captured, such as the microphone handover between two
     * parts), so what follows stays on the take's timeline. Only while no [AudioTrackRecorder] feeds [writer].
     */
    fun padSilence(frames: Long) = ring.padSilence(frames)

    /** The writer's error the first time it is asked for (each part reports its own problems once), else null. */
    fun newError(): String? = ring.error?.takeUnless { errorReported }?.also { errorReported = true }

    /** Drains the buffer, patches the header, then syncs and keeps the file, or deletes it. Blocks on I/O. */
    fun finish(keep: Boolean): WavReport {
        runCatching { ring.close() }
        if (keep) {
            runCatching { file.sync() }
            runCatching { file.close() }
        } else {
            runCatching { file.delete() }
        }
        return WavReport(droppedFrames, ring.overruns, config.sampleRate, newError(), kept = keep)
    }

    companion object {
        /** Seconds of audio the drive may fall behind before buffers are dropped: 768 kB at 48 kHz stereo. */
        const val BUFFER_SECONDS = 4

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

/** How the WAV of a take ended (D86): frames lost to a slow drive (written as silence) and a late error. */
data class WavReport(val droppedFrames: Long, val overruns: Int, val sampleRate: Int, val error: String?, val kept: Boolean) {
    /** Seconds of silence put in place of audio the drive could not take. */
    val droppedSeconds: Double get() = droppedFrames.toDouble() / sampleRate

    /** Whether the user should hear about it: a kept file with gaps. */
    val hasGaps: Boolean get() = kept && droppedFrames > 0
}
