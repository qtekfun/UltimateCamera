package com.qtekfun.ultimatecamera.core.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Which kind of local link a measurement went over; chosen by whoever runs the test. */
@Serializable
enum class NetworkLink {
    WIFI_ROUTER,
    PHONE_HOTSPOT
}

@Serializable
data class NetworkLatencyResult(
    val target: String,
    val packetBytes: Int,
    val rateHz: Int,
    val stats: LatencyStats,
    val error: String? = null,
    val link: NetworkLink? = null
)

/**
 * Measures LAN round-trip time with UDP packets sized like a video slice, at a frame-like rate. Until the WebRTC stack is
 * chosen this is the Phase 0 baseline for the monitor link (Wi-Fi vs hotspot); one-way video latency is roughly half the
 * RTT plus encode, jitter buffer and decode time.
 *
 * Wire format: 8-byte sequence number + 8-byte send time (System.nanoTime of the sender), padded to the packet size.
 * The echo side sends every packet back unchanged.
 */
object UdpLatencyProbe {
    const val DEFAULT_PORT = 47_800

    /** Echoes packets back to their sender until the coroutine is cancelled. */
    suspend fun serve(port: Int = DEFAULT_PORT) = withContext(Dispatchers.IO) {
        DatagramSocket(port).use { socket ->
            socket.soTimeout = 500
            val buffer = ByteArray(MAX_PACKET)
            while (coroutineContext.isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    socket.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
                } catch (_: SocketTimeoutException) {
                    // Check for cancellation and keep listening.
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Probe boundary: a network failure is a result, not a crash.
    suspend fun measure(host: String, port: Int = DEFAULT_PORT, count: Int = 600, rateHz: Int = 60, packetBytes: Int = 1_200): NetworkLatencyResult =
        withContext(Dispatchers.IO) {
            require(packetBytes in HEADER..MAX_PACKET && count > 0 && rateHz > 0)
            val rtts = mutableListOf<Double>()
            try {
                val address = InetAddress.getByName(host)
                DatagramSocket().use { socket ->
                    socket.soTimeout = 1
                    val out = ByteArray(packetBytes)
                    val inBuf = ByteArray(MAX_PACKET)
                    val seen = BooleanArray(count)
                    val interval = 1_000_000_000L / rateHz
                    val start = System.nanoTime()
                    val deadline = start + count * interval + DRAIN_NS
                    var next = 0
                    while (System.nanoTime() < deadline && rtts.size < count) {
                        val now = System.nanoTime()
                        if (next < count && now >= start + next * interval) {
                            ByteBuffer.wrap(out).putLong(next.toLong()).putLong(now)
                            socket.send(DatagramPacket(out, out.size, address, port))
                            next++
                        }
                        val packet = DatagramPacket(inBuf, inBuf.size)
                        try {
                            socket.receive(packet)
                            val received = System.nanoTime()
                            val header = ByteBuffer.wrap(packet.data, 0, HEADER)
                            val seq = header.long.toInt()
                            val sentAt = header.long
                            if (seq in 0 until count && !seen[seq]) {
                                seen[seq] = true
                                rtts += (received - sentAt) / 1_000_000.0
                            }
                        } catch (_: SocketTimeoutException) {
                            // Nothing arrived in this 1 ms slot.
                        }
                    }
                }
                NetworkLatencyResult("$host:$port", packetBytes, rateHz, LatencyStats.of(count, rtts))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NetworkLatencyResult("$host:$port", packetBytes, rateHz, LatencyStats.of(count, rtts), error = "${e.javaClass.simpleName}: ${e.message}")
            }
        }

    /** Non-loopback IPv4 addresses of this phone, to show on the echo side. */
    fun localAddresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { iface -> iface.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${iface.name} ${it.hostAddress}" } }

    private const val HEADER = 16
    private const val MAX_PACKET = 1_472
    private const val DRAIN_NS = 1_000_000_000L
}
