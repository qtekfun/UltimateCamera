package com.qtekfun.ultimatecamera.core.net.monitor

import java.nio.ByteBuffer

/**
 * Measurement messages on the "control" data channel. They travel as binary data-channel messages: one type byte, then
 * a big-endian payload. Text messages on the same channel are left free for the real control protocol (REC, exposure).
 *
 * Times are the camera phone's System.nanoTime and only ever compared with that same clock, so no clock sync is needed.
 */
internal sealed interface LinkMessage {
    fun encode(): ByteArray

    /** Monitor to camera: the frame counter it just read from a decoded frame. */
    data class Echo(val counter: Int) : LinkMessage {
        override fun encode(): ByteArray = ByteBuffer.allocate(1 + Int.SIZE_BYTES).put(ECHO).putInt(counter).array()
    }

    /** Camera to monitor: answer with a [Pong] carrying [sentNanos] unchanged. */
    data class Ping(val sentNanos: Long) : LinkMessage {
        override fun encode(): ByteArray = ByteBuffer.allocate(1 + Long.SIZE_BYTES).put(PING).putLong(sentNanos).array()
    }

    data class Pong(val sentNanos: Long) : LinkMessage {
        override fun encode(): ByteArray = ByteBuffer.allocate(1 + Long.SIZE_BYTES).put(PONG).putLong(sentNanos).array()
    }

    companion object {
        private const val ECHO: Byte = 1
        private const val PING: Byte = 2
        private const val PONG: Byte = 3

        /** Reads one message from [data] (from its position); null for an unknown type or a short message. */
        fun decode(data: ByteBuffer): LinkMessage? {
            if (!data.hasRemaining()) return null
            val type = data.get()
            return when {
                type == ECHO && data.remaining() >= Int.SIZE_BYTES -> Echo(data.int)
                type == PING && data.remaining() >= Long.SIZE_BYTES -> Ping(data.long)
                type == PONG && data.remaining() >= Long.SIZE_BYTES -> Pong(data.long)
                else -> null
            }
        }
    }
}
