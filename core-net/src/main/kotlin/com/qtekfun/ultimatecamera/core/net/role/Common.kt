package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.control.ControlConnection
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import java.io.IOException
import kotlin.random.Random

/**
 * Where a role state machine sends messages for one peer: a [ControlConnection] in the app ([ConnectionSink]), a fake
 * in tests. [send] returns false when the link is broken (the machine then treats the peer as disconnected).
 */
interface MessageSink {
    fun send(message: ControlMessage): Boolean

    fun close()
}

/** [MessageSink] over a real connection; sends happen on the caller's thread (control messages are small). */
class ConnectionSink(private val connection: ControlConnection) : MessageSink {
    override fun send(message: ControlMessage): Boolean = try {
        connection.send(message)
        true
    } catch (_: IOException) {
        false
    }

    override fun close() = connection.close()
}

/**
 * Exponential backoff with "equal jitter": attempt n waits between half and all of min([maxNanos], [baseNanos]·2ⁿ), so
 * phones that lost the same Wi-Fi do not all redial in the same millisecond, and a link that drops is retried within a
 * quarter of a second.
 */
class Backoff(private val baseNanos: Long = 250_000_000L, private val maxNanos: Long = 5_000_000_000L, private val random: Random = Random.Default) {
    init {
        require(baseNanos > 0 && maxNanos >= baseNanos)
    }

    fun delayNanos(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_SHIFT)
        val ceiling = (baseNanos shl shift).coerceAtMost(maxNanos)
        val half = ceiling / 2
        return half + random.nextLong(half + 1)
    }

    private companion object {
        const val MAX_SHIFT = 20
    }
}

/** Timing of the control links; defaults from [com.qtekfun.ultimatecamera.core.net.control.ControlProtocol]. */
data class LinkTiming(
    val heartbeatIntervalNanos: Long = 1_000_000_000L,
    val linkTimeoutNanos: Long = 4_000_000_000L,
    /** A command not acknowledged within this time (link down meanwhile) is given up and reported as timed out. */
    val commandLifetimeNanos: Long = 15_000_000_000L,
    /** How often a camera re-sends its full status even without changes. */
    val statusIntervalNanos: Long = 5_000_000_000L
)

/** Heartbeat bookkeeping shared by both ends: what to echo and the measured round trip. */
internal class HeartbeatState {
    var lastPeerSentNanos: Long? = null
    var lastPeerReceivedAt: Long = 0
    var rttNanos: Long? = null

    fun onHeartbeat(message: ControlMessage.Heartbeat, now: Long) {
        lastPeerSentNanos = message.sentNanos
        lastPeerReceivedAt = now
        val echo = message.echoNanos
        val held = message.echoHeldNanos
        if (echo != null && held != null) {
            val rtt = now - echo - held
            if (rtt >= 0) rttNanos = rtt
        }
    }

    fun outgoing(now: Long): ControlMessage.Heartbeat {
        val echo = lastPeerSentNanos
        return ControlMessage.Heartbeat(sentNanos = now, echoNanos = echo, echoHeldNanos = echo?.let { now - lastPeerReceivedAt })
    }
}

/** Small insertion-ordered LRU map. */
internal class Lru<K, V>(private val capacity: Int) : LinkedHashMap<K, V>(capacity, LOAD, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity

    private companion object {
        const val LOAD = 0.75f
    }
}
