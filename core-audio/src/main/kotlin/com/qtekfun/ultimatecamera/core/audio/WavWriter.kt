package com.qtekfun.ultimatecamera.core.audio

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Receives processed interleaved 16-bit PCM from the audio thread (e.g. the separate WAV of REQ-AUD-003). */
fun interface PcmSink {
    /** Takes [count] samples of [samples] starting at [offset]. Called on the audio thread: must not allocate. */
    fun write(samples: ShortArray, offset: Int, count: Int)
}

/**
 * Streams 16-bit PCM into a RIFF/WAVE file (REQ-AUD-003). The header is written with zero sizes first and patched
 * every [headerEveryBytes] and on [close], so a take cut short by a crash or a pulled drive still leaves a playable
 * file up to the last patch (about one second by default).
 *
 * Buffers are allocated once; [write] does not allocate (owner rule: modest phones). Sizes above 4 GiB (over six hours
 * of 48 kHz stereo) are capped in the header, as RIFF cannot express more.
 */
class WavWriter private constructor(
    private val channel: FileChannel,
    private val sampleRate: Int,
    private val channels: Int,
    private val closeChannel: Boolean,
    private val headerEveryBytes: Long,
    private val bext: BextChunk?
) : AutoCloseable,
    PcmSink {

    /** Writes into [file], replacing it; with [bext] a Broadcast Wave file (start timecode, D58). */
    constructor(file: File, sampleRate: Int, channels: Int, headerEveryBytes: Long = DEFAULT_HEADER_EVERY_BYTES, bext: BextChunk? = null) :
        this(RandomAccessFile(file, "rw").apply { setLength(0) }.channel, sampleRate, channels, closeChannel = true, headerEveryBytes, bext)

    /**
     * Writes into an open, empty, seekable descriptor (e.g. a SAF or MediaStore file from core-storage). The descriptor
     * stays open: its owner syncs and closes it after [close].
     */
    constructor(fd: FileDescriptor, sampleRate: Int, channels: Int, headerEveryBytes: Long = DEFAULT_HEADER_EVERY_BYTES, bext: BextChunk? = null) :
        this(FileOutputStream(fd).channel, sampleRate, channels, closeChannel = false, headerEveryBytes, bext)

    /** Bytes before the PCM: [HEADER_SIZE], plus the `bext` chunk of a Broadcast Wave file. */
    val headerSize: Int = headerSize(bext != null)

    private val headerBuffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
    private var bytes = ByteArray(INITIAL_BUFFER_BYTES)
    private var wrapped = ByteBuffer.wrap(bytes)
    private var dataBytes = 0L
    private var sincePatch = 0L

    /** Bytes of PCM written so far. */
    val dataSize: Long get() = dataBytes

    init {
        require(sampleRate > 0 && channels in 1..MAX_CHANNELS) { "invalid format $sampleRate Hz x $channels" }
        writeHeader()
        channel.position(headerSize.toLong())
    }

    /** Appends the first [count] samples of [samples]. */
    fun write(samples: ShortArray, count: Int) = write(samples, 0, count)

    override fun write(samples: ShortArray, offset: Int, count: Int) {
        if (count <= 0) return
        val needed = count * 2
        if (bytes.size < needed) { // Only when a bigger buffer than ever before arrives.
            bytes = ByteArray(needed)
            wrapped = ByteBuffer.wrap(bytes)
        }
        for (i in 0 until count) {
            val s = samples[offset + i].toInt()
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr BYTE_BITS).toByte()
        }
        wrapped.clear()
        wrapped.limit(needed)
        while (wrapped.hasRemaining()) channel.write(wrapped)
        dataBytes += needed
        sincePatch += needed
        if (sincePatch >= headerEveryBytes) {
            sincePatch = 0
            writeHeader()
        }
    }

    override fun close() {
        try {
            writeHeader()
        } finally {
            if (closeChannel) channel.close()
        }
    }

    /** Positional write: does not move the append position. */
    private fun writeHeader() {
        headerBuffer.clear()
        fill(headerBuffer, sampleRate, channels, dataBytes, bext)
        headerBuffer.flip()
        var at = 0L
        while (headerBuffer.hasRemaining()) at += channel.write(headerBuffer, at)
    }

    companion object {
        /** Header of a plain WAV (RIFF, fmt and data chunk headers). */
        const val HEADER_SIZE = 44

        fun headerSize(broadcast: Boolean): Int = HEADER_SIZE + if (broadcast) BextChunk.CHUNK_SIZE else 0
        private const val MAX_CHANNELS = 8
        private const val INITIAL_BUFFER_BYTES = 8 * 1024
        private const val BYTE_BITS = 8

        /** About one second of 48 kHz stereo 16-bit. */
        const val DEFAULT_HEADER_EVERY_BYTES = 192_000L

        /** Largest data size a RIFF header can hold (the RIFF size field is data + 36, unsigned 32-bit). */
        const val MAX_DATA_BYTES = 0xFFFF_FFFFL - 36

        fun header(sampleRate: Int, channels: Int, dataSize: Long, bext: BextChunk? = null): ByteArray =
            ByteBuffer.allocate(headerSize(bext != null)).order(ByteOrder.LITTLE_ENDIAN).also { fill(it, sampleRate, channels, dataSize, bext) }.array()

        private fun fill(buffer: ByteBuffer, sampleRate: Int, channels: Int, dataSize: Long, bext: BextChunk?) {
            // The RIFF size counts everything after its own field, so the data limit shrinks by the bext chunk.
            val extra = if (bext != null) BextChunk.CHUNK_SIZE else 0
            val size = dataSize.coerceIn(0, MAX_DATA_BYTES - extra)
            buffer.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
            buffer.putInt((36 + extra + size).toInt()) // Unsigned 32-bit: the low bits of a Long are right.
            buffer.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())
            // BWF puts bext before fmt; readers that do not know it skip it by its size.
            bext?.writeTo(buffer)
            buffer.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
            buffer.putInt(16)
            buffer.putShort(1) // PCM
            buffer.putShort(channels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(sampleRate * channels * 2)
            buffer.putShort((channels * 2).toShort())
            buffer.putShort(16)
            buffer.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
            buffer.putInt(size.toInt())
        }
    }
}
