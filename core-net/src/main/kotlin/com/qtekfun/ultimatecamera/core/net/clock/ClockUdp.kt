package com.qtekfun.ultimatecamera.core.net.clock

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Clock-sync datagram (same request/echo pattern as `UdpLatencyProbe`, but authenticated). 64 bytes, big-endian:
 * `"UCK1" | type u8 | 3 reserved | keyId 8 | seq 8 | t0 8 | t1 8 | t2 8 | HMAC-SHA256(clockKey, bytes 0..47) truncated to 16`.
 * The client fills t0; the server t1 (receive) and t2 (send) on the common clock. [keyId] (first 8 bytes of
 * SHA-256(clockKey)) lets the server pick the session key, so a LAN device without a session cannot shift the clock;
 * a delayed or replayed packet only looks slower and is dropped by the minimum-delay filter.
 */
data class ClockPacket(val type: Byte, val keyId: Long, val seq: Long, val t0: Long, val t1: Long = 0, val t2: Long = 0) {
    fun encode(key: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE)
        buffer.put(MAGIC).put(type).put(ByteArray(RESERVED)).putLong(keyId).putLong(seq).putLong(t0).putLong(t1).putLong(t2)
        buffer.put(mac(key, buffer.array()))
        return buffer.array()
    }

    companion object {
        const val SIZE = 64
        const val REQUEST: Byte = 1
        const val RESPONSE: Byte = 2
        private val MAGIC = byteArrayOf('U'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
        private const val RESERVED = 3
        private const val BODY = 48
        private const val MAC_BYTES = 16
        private const val KEY_ID_OFFSET = 8

        fun keyId(key: ByteArray): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(key)).long

        /** Reads the key id of a datagram without verifying it; null if it is not a clock packet. */
        fun peekKeyId(data: ByteArray, length: Int): Long? {
            if (length != SIZE || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
            return ByteBuffer.wrap(data, KEY_ID_OFFSET, Long.SIZE_BYTES).long
        }

        /** Verifies and decodes; null on a wrong size, magic or MAC. */
        fun decode(data: ByteArray, length: Int, key: ByteArray): ClockPacket? {
            if (peekKeyId(data, length) == null) return null
            val expected = mac(key, data.copyOfRange(0, SIZE))
            if (!MessageDigest.isEqual(expected, data.copyOfRange(BODY, SIZE))) return null
            val b = ByteBuffer.wrap(data, 0, SIZE)
            b.position(MAGIC.size)
            val type = b.get()
            b.position(KEY_ID_OFFSET)
            return ClockPacket(type, b.long, b.long, b.long, b.long, b.long)
        }

        private fun mac(key: ByteArray, packet: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(packet, 0, BODY)
            doFinal()
        }.copyOf(MAC_BYTES)
    }
}

/**
 * Clock source side (the master): answers authenticated requests with its common time. Keys are added per session
 * ([addKey] after a control handshake) and removed when it ends.
 */
class ClockServer(private val clock: SourceClock) {
    private val keys = ConcurrentHashMap<Long, ByteArray>()

    fun addKey(key: ByteArray) {
        keys[ClockPacket.keyId(key)] = key.copyOf()
    }

    fun removeKey(key: ByteArray) {
        keys.remove(ClockPacket.keyId(key))
    }

    /** Answer to one request datagram, or null to drop it. Pure apart from reading the clock; used by [serve]. */
    fun respond(data: ByteArray, length: Int, receivedCommon: Long): ByteArray? {
        val key = ClockPacket.peekKeyId(data, length)?.let { keys[it] } ?: return null
        val request = ClockPacket.decode(data, length, key)?.takeIf { it.type == ClockPacket.REQUEST } ?: return null
        return request.copy(type = ClockPacket.RESPONSE, t1 = receivedCommon, t2 = clock.now()).encode(key)
    }

    /** Serves on [port] until cancelled. */
    suspend fun serve(port: Int = DEFAULT_PORT, onBound: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            socket.soTimeout = POLL_MS
            onBound(socket.localPort)
            val buffer = ByteArray(ClockPacket.SIZE + 1)
            while (true) {
                coroutineContext.ensureActive()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val received = clock.now()
                respond(packet.data, packet.length, received)?.let { socket.send(DatagramPacket(it, it.size, packet.socketAddress)) }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 47_803
        private const val POLL_MS = 500
    }
}

/**
 * Follower side (a camera): sends requests to the clock source and feeds [estimator]. A burst at start gives a usable
 * estimate within about a second; afterwards one request every [steadyIntervalMs] tracks drift cheaply (one 64-byte
 * datagram each way).
 */
class ClockClient(
    private val host: String,
    private val port: Int,
    private val key: ByteArray,
    private val local: MonotonicClock,
    private val estimator: ClockEstimator
) {
    private val keyId = ClockPacket.keyId(key)

    /** Runs until cancelled. */
    suspend fun run(burst: Int = BURST, burstIntervalMs: Long = BURST_INTERVAL_MS, steadyIntervalMs: Long = STEADY_INTERVAL_MS) = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.soTimeout = REPLY_TIMEOUT_MS
            val address = InetAddress.getByName(host)
            var seq = 0L
            while (true) {
                coroutineContext.ensureActive()
                exchange(socket, address, seq++)?.let { estimator.add(it) }
                delay(if (seq < burst) burstIntervalMs else steadyIntervalMs)
            }
        }
    }

    /** One request/response; null on timeout or a mismatched/forged reply. */
    fun exchange(socket: DatagramSocket, address: InetAddress, seq: Long): ClockSample? {
        val t0 = local.nanos()
        val out = ClockPacket(ClockPacket.REQUEST, keyId, seq, t0).encode(key)
        socket.send(DatagramPacket(out, out.size, address, port))
        val buffer = ByteArray(ClockPacket.SIZE + 1)
        val deadline = t0 + REPLY_TIMEOUT_MS * NANOS_PER_MS
        while (local.nanos() < deadline) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                return null
            }
            val t3 = local.nanos()
            val reply = ClockPacket.decode(packet.data, packet.length, key) ?: continue
            if (reply.type == ClockPacket.RESPONSE && reply.seq == seq && reply.t0 == t0) return ClockSample(t0, reply.t1, reply.t2, t3)
        }
        return null
    }

    companion object {
        const val BURST = 16
        const val BURST_INTERVAL_MS = 50L
        const val STEADY_INTERVAL_MS = 2_000L
        private const val REPLY_TIMEOUT_MS = 300
        private const val NANOS_PER_MS = 1_000_000L
    }
}
