package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.discovery.DiscoveredService
import com.qtekfun.ultimatecamera.core.net.discovery.DiscoveryEvent
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A phone seen on the LAN, merged over all its mDNS adverts (the pairing listener and the camera's control server). */
data class NearbyDevice(val deviceId: String, val name: String, val role: DeviceRole, val endpoints: List<Endpoint>)

/**
 * Live state of the DNS-SD browse (`_ultimatecamera._tcp`) shown on the multicamera screen. Pure: [reduce] folds
 * discovery events; adverts are kept per mDNS instance name, because one phone may advertise more than once.
 */
data class DiscoveryStatus(val state: State = State.IDLE, val failureCode: Int? = null, val adverts: Map<String, DiscoveredService> = emptyMap()) {
    enum class State { IDLE, SEARCHING, FAILED }

    /** Other phones, one entry per device id, endpoints of all its adverts merged. */
    fun devices(ownId: String): List<NearbyDevice> = adverts.values
        .filter { it.advert.deviceId != ownId }
        .groupBy { it.advert.deviceId }
        .map { (id, services) ->
            val first = services.minBy { it.advert.name.length } // "Name" before "Name (2)"
            NearbyDevice(id, first.advert.name, first.advert.role, services.flatMap { it.endpoints }.distinct())
        }
        .sortedBy { it.name.lowercase() }

    fun isOnline(deviceId: String): Boolean = adverts.values.any { it.advert.deviceId == deviceId }

    fun reduce(event: DiscoveryEvent): DiscoveryStatus = when (event) {
        is DiscoveryEvent.Found -> copy(state = State.SEARCHING, failureCode = null, adverts = adverts + (event.service.advert.name to event.service))
        is DiscoveryEvent.Lost -> copy(adverts = adverts.filterKeys { it != event.name })
        is DiscoveryEvent.Failed -> copy(state = State.FAILED, failureCode = event.code)
    }
}

/** Browses while started and publishes a [DiscoveryStatus]. Discovery callbacks come on platform threads. */
class DiscoveryWatcher(private val discovery: ServiceDiscovery) {
    private val _status = MutableStateFlow(DiscoveryStatus())
    val status: StateFlow<DiscoveryStatus> = _status.asStateFlow()
    private var handle: Closeable? = null

    @Synchronized
    fun start() {
        if (handle != null) return
        _status.value = DiscoveryStatus(state = DiscoveryStatus.State.SEARCHING)
        handle = runCatching { discovery.browse { event -> _status.update { it.reduce(event) } } }
            .onFailure { _status.value = DiscoveryStatus(state = DiscoveryStatus.State.FAILED) }
            .getOrNull()
    }

    @Synchronized
    fun stop() {
        handle?.let { runCatching { it.close() } }
        handle = null
        _status.value = DiscoveryStatus()
    }
}
