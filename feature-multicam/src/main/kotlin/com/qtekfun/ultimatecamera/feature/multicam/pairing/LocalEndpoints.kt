package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.PairingPayload
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** The addresses this phone puts in its pairing QR: IPv4 of the Wi-Fi and hotspot interfaces first. */
object LocalEndpoints {
    /** One local address: interface name and IPv4 literal. */
    data class Candidate(val interfaceName: String, val address: String)

    /** Pure ranking: Wi-Fi (`wlan*`) first, then hotspot/tethering, then the rest; loopback and link-local dropped. */
    fun pick(candidates: List<Candidate>, port: Int): List<Endpoint> = candidates
        .filterNot { it.address.startsWith("127.") || it.address.startsWith("169.254.") }
        // Mobile data: never reachable from the other phone on the LAN.
        .filterNot { it.interfaceName.startsWith("rmnet") || it.interfaceName.startsWith("ccmni") }
        .filter { PairingPayload.isValidHost(it.address) }
        .sortedBy { rank(it.interfaceName) }
        .map { Endpoint(it.address, port) }
        .distinct()
        .take(PairingPayload.MAX_ENDPOINTS)

    private fun rank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") -> 1
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("eth") -> 2
        else -> 3
    }

    /** The phone's current IPv4 addresses (any interface that is up). */
    fun current(): List<Candidate> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { a -> a.hostAddress?.let { Candidate(nif.name, it) } } }
    } catch (_: SocketException) {
        emptyList()
    }
}
