package com.qtekfun.ultimatecamera.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams 16-bit PCM into a RIFF/WAVE file (REQ-AUD-003 groundwork). The header is written with zero sizes first and
 * patched on [close], so a recording cut short still leaves a file with a valid header up to the last [close].
 */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L

    init {
        require(sampleRate > 0 && channels in 1..MAX_CHANNELS) { "invalid format $sampleRate Hz x $channels" }
        out.write(header(0))
    }

    private var bytes = ByteArray(0)

    /** Appends [count] samples. The byte buffer is reused, so steady-state writes do not allocate. */
    fun write(samples: ShortArray, count: Int) {
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        out.write(bytes, 0, count * 2)
        dataBytes += count * 2L
    }

    override fun close() {
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
    }

    internal fun header(dataSize: Long): ByteArray = Companion.header(sampleRate, channels, dataSize)

    companion object {
        const val HEADER_SIZE = 44
        private const val MAX_CHANNELS = 8

        fun header(sampleRate: Int, channels: Int, dataSize: Long): ByteArray {
            val byteRate = sampleRate * channels * 2
            return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt((36 + dataSize).toInt())
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((channels * 2).toShort())
                putShort(16)
                put("data".toByteArray())
                putInt(dataSize.toInt())
            }.array()
        }
    }
}
