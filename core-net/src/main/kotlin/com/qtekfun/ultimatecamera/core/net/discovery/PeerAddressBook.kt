package com.qtekfun.ultimatecamera.core.net.discovery

import com.qtekfun.ultimatecamera.core.net.control.ControlProtocol
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint

/**
 * Where to dial each paired device: addresses discovered on the LAN now (DHCP may have changed them) first, then the
 * ones stored at pairing or last connection. Fed by [ServiceDiscovery] events; adverts of unpaired devices, of this
 * phone and of incompatible protocol versions are ignored. Thread-safe (discovery callbacks come on platform threads).
 */
class PeerAddressBook(private val ownId: String, private val isPaired: (String) -> Boolean) {
    private val lock = Any()
    private val discovered = HashMap<String, List<Endpoint>>()
    private val nameToId = HashMap<String, String>()

    fun onEvent(event: DiscoveryEvent) = synchronized(lock) {
        when (event) {
            is DiscoveryEvent.Found -> {
                val advert = event.service.advert
                if (advert.deviceId != ownId && isPaired(advert.deviceId) && advert.protocol >= ControlProtocol.MIN_VERSION) {
                    discovered[advert.deviceId] = event.service.endpoints
                    nameToId[advert.name] = advert.deviceId
                }
            }
            is DiscoveryEvent.Lost -> (event.deviceId ?: nameToId[event.name])?.let {
                discovered.remove(it)
                nameToId.remove(event.name)
            }
            is DiscoveryEvent.Failed -> Unit
        }
    }

    /** Currently advertised on the LAN. */
    fun isOnline(deviceId: String): Boolean = synchronized(lock) { deviceId in discovered }

    /** Dial order for [deviceId]: discovered first, then [stored], without repeats. */
    fun endpointsFor(deviceId: String, stored: List<Endpoint>): List<Endpoint> = synchronized(lock) {
        ((discovered[deviceId] ?: emptyList()) + stored).distinct()
    }
}
