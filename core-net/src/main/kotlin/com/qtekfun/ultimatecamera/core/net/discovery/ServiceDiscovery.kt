package com.qtekfun.ultimatecamera.core.net.discovery

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.io.Closeable

/**
 * What a phone advertises on the LAN (REQ-NET-002, docs/01: DNS-SD over mDNS). The service type is [SERVICE_TYPE]; the
 * instance name is the user-visible device name (the platform may append " (2)" on a clash); the port is the control
 * port. The TXT record carries only what is needed to match a paired device, never a key.
 */
data class ServiceAdvert(val deviceId: String, val name: String, val role: DeviceRole, val port: Int, val protocol: Int) {
    init {
        require(Secrets.isValidDeviceId(deviceId)) { "bad device id" }
        require(port in 1..Endpoint.MAX_PORT)
    }

    companion object {
        const val SERVICE_TYPE = "_ultimatecamera._tcp"
    }
}

/** A resolved advert from another phone: its TXT data plus the addresses it was resolved to. */
data class DiscoveredService(val advert: ServiceAdvert, val endpoints: List<Endpoint>)

sealed interface DiscoveryEvent {
    data class Found(val service: DiscoveredService) : DiscoveryEvent

    /** The advert with this mDNS instance [name] went away (phone left the network or stopped advertising). */
    data class Lost(val name: String, val deviceId: String?) : DiscoveryEvent

    /** Discovery could not start or stopped; [code] is the platform's error code. */
    data class Failed(val code: Int) : DiscoveryEvent
}

/**
 * DNS-SD behind an interface so the role logic and tests never touch NsdManager. The Android implementation is
 * [NsdServiceDiscovery]; tests use an in-memory fake.
 */
interface ServiceDiscovery {
    /** Starts advertising; closing the returned handle unregisters. */
    fun advertise(advert: ServiceAdvert, onFailed: (code: Int) -> Unit = {}): Closeable

    /** Starts browsing for [ServiceAdvert.SERVICE_TYPE]; events arrive on an arbitrary thread; closing stops. */
    fun browse(onEvent: (DiscoveryEvent) -> Unit): Closeable
}

/**
 * TXT record keys and values. Keys are short (DNS-SD recommends ≤ 9 characters); values are ASCII.
 * - `id`: device id, `role`: `master`/`camera`/`monitor`, `pv`: control protocol version, `txtvers`: this TXT layout.
 */
object DiscoveryTxt {
    const val TXT_VERSION = 1
    const val KEY_TXT_VERSION = "txtvers"
    const val KEY_ID = "id"
    const val KEY_ROLE = "role"
    const val KEY_PROTOCOL = "pv"

    fun encode(advert: ServiceAdvert): Map<String, String> = linkedMapOf(
        KEY_TXT_VERSION to TXT_VERSION.toString(),
        KEY_ID to advert.deviceId,
        KEY_ROLE to advert.role.wire,
        KEY_PROTOCOL to advert.protocol.toString()
    )

    /**
     * Rebuilds an advert from an mDNS instance [name], [port] and raw TXT [attributes] (as NsdServiceInfo returns them:
     * value bytes may be null for a key without value). Null when it is not one of ours or is malformed; unknown keys
     * are ignored, so a newer TXT layout still matches.
     */
    fun decode(name: String, port: Int, attributes: Map<String, ByteArray?>): ServiceAdvert? {
        fun text(key: String) = attributes[key]?.toString(Charsets.US_ASCII)
        val id = text(KEY_ID)?.takeIf { Secrets.isValidDeviceId(it) } ?: return null
        val role = text(KEY_ROLE)?.let { DeviceRole.fromWire(it) } ?: return null
        val protocol = text(KEY_PROTOCOL)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (port !in 1..Endpoint.MAX_PORT || name.isBlank()) return null
        return ServiceAdvert(id, name, role, port, protocol)
    }
}
