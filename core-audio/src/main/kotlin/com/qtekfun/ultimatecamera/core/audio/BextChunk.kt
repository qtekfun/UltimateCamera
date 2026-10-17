package com.qtekfun.ultimatecamera.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Broadcast Wave `bext` chunk (EBU Tech 3285, version 1) of a take's WAV (REQ-NET-005, D58). Editors (DaVinci
 * Resolve, Premiere Pro, Final Cut via the BWF importer, Avid) read [timeReferenceSamples] as the clip's start
 * timecode: samples since local midnight of the first sample. Text fields are ASCII, cut to their fixed sizes.
 */
data class BextChunk(
    val description: String = "",
    val originator: String = ORIGINATOR,
    val originatorReference: String = "",
    /** `yyyy-mm-dd`. */
    val originationDate: String = "",
    /** `hh:mm:ss` (the spec allows any separator). */
    val originationTime: String = "",
    val timeReferenceSamples: Long = 0
) {
    init {
        require(timeReferenceSamples >= 0) { "time reference before midnight" }
    }

    /** Writes the whole chunk (id, size and body, [CHUNK_SIZE] bytes) at [buffer]'s position (little-endian). */
    fun writeTo(buffer: ByteBuffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(ID.toByteArray(Charsets.US_ASCII)).putInt(BODY_SIZE)
        putText(buffer, description, DESCRIPTION)
        putText(buffer, originator, ORIGINATOR_LEN)
        putText(buffer, originatorReference, REFERENCE_LEN)
        putText(buffer, originationDate, DATE_LEN)
        putText(buffer, originationTime, TIME_LEN)
        buffer.putInt(timeReferenceSamples.toInt()) // Low 32 bits.
        buffer.putInt((timeReferenceSamples ushr Int.SIZE_BITS).toInt())
        buffer.putShort(VERSION)
        buffer.put(ByteArray(UMID_LEN + LOUDNESS_LEN + RESERVED_LEN))
    }

    companion object {
        const val ID = "bext"
        const val ORIGINATOR = "UltimateCamera"

        /** Fixed body of a version-1 chunk without coding history. */
        const val BODY_SIZE = 602

        /** Chunk header plus body. */
        const val CHUNK_SIZE = 8 + BODY_SIZE

        private const val DESCRIPTION = 256
        private const val ORIGINATOR_LEN = 32
        private const val REFERENCE_LEN = 32
        private const val DATE_LEN = 10
        private const val TIME_LEN = 8
        private const val UMID_LEN = 64
        private const val LOUDNESS_LEN = 10
        private const val RESERVED_LEN = 180
        private const val VERSION: Short = 1
        private const val NANOS_PER_S = 1_000_000_000L
        private const val DAY_NANOS = 86_400L * NANOS_PER_S
        private const val CHUNK_HEADER = 8
        private const val RIFF_HEADER = 12

        /**
         * Samples at [sampleRate] from local midnight to [epochNanos] (Unix epoch) in a zone [utcOffsetSeconds] east of
         * UTC, rounded to the nearest sample: the BWF TimeReference of a WAV whose first sample is at [epochNanos].
         */
        fun samplesSinceMidnight(epochNanos: Long, utcOffsetSeconds: Int, sampleRate: Int): Long {
            val sinceMidnight = Math.floorMod(epochNanos + utcOffsetSeconds * NANOS_PER_S, DAY_NANOS)
            // Seconds and the remainder separately: a day of nanoseconds times 192 kHz would overflow a Long.
            val seconds = sinceMidnight / NANOS_PER_S
            val rest = sinceMidnight % NANOS_PER_S
            return seconds * sampleRate + (rest * sampleRate + NANOS_PER_S / 2) / NANOS_PER_S
        }

        /** Reads the `bext` chunk of a RIFF/WAVE file's bytes; null when there is none (or the file is not a WAV). */
        fun parse(wav: ByteArray): BextChunk? {
            if (wav.size < RIFF_HEADER || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            var at = RIFF_HEADER
            while (at + CHUNK_HEADER <= wav.size) {
                val id = String(wav, at, 4, Charsets.US_ASCII)
                val size = buffer.getInt(at + 4).toLong() and 0xFFFF_FFFFL
                if (id == ID && size >= BODY_SIZE && at + CHUNK_HEADER + BODY_SIZE <= wav.size) {
                    buffer.position(at + CHUNK_HEADER)
                    return read(buffer)
                }
                at += CHUNK_HEADER + size.toInt() + (size.toInt() and 1)
            }
            return null
        }

        private fun read(buffer: ByteBuffer): BextChunk {
            val description = text(buffer, DESCRIPTION)
            val originator = text(buffer, ORIGINATOR_LEN)
            val reference = text(buffer, REFERENCE_LEN)
            val date = text(buffer, DATE_LEN)
            val time = text(buffer, TIME_LEN)
            val low = buffer.int.toLong() and 0xFFFF_FFFFL
            val high = buffer.int.toLong() and 0xFFFF_FFFFL
            return BextChunk(description, originator, reference, date, time, (high shl Int.SIZE_BITS) or low)
        }

        private fun putText(buffer: ByteBuffer, value: String, length: Int) {
            val bytes = value.toByteArray(Charsets.US_ASCII).copyOf(length) // Cut or NUL-padded.
            buffer.put(bytes)
        }

        private fun text(buffer: ByteBuffer, length: Int): String {
            val bytes = ByteArray(length).also { buffer.get(it) }
            val end = bytes.indexOf(0).let { if (it < 0) length else it }
            return String(bytes, 0, end, Charsets.US_ASCII)
        }
    }
}
