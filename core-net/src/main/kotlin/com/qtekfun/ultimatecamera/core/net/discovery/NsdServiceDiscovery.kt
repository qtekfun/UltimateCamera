package com.qtekfun.ultimatecamera.core.net.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import java.io.Closeable
import java.net.Inet6Address
import java.net.InetAddress
import java.util.ArrayDeque

/**
 * [ServiceDiscovery] over Android's NsdManager (the system mDNS responder). Needs no permission beyond INTERNET and no
 * multicast lock: the system daemon does the multicast, not the app. Verified by hand on phones (docs/TESTING.md), not
 * in unit tests.
 *
 * Resolution uses `resolveService`, which before API 34 handles one request at a time, so resolves are queued.
 */
class NsdServiceDiscovery(context: Context) : ServiceDiscovery {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override fun advertise(advert: ServiceAdvert, onFailed: (code: Int) -> Unit): Closeable {
        val info = NsdServiceInfo().apply {
            serviceName = advert.name
            serviceType = ServiceAdvert.SERVICE_TYPE
            port = advert.port
            DiscoveryTxt.encode(advert).forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = onFailed(errorCode)

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return Closeable { runCatching { nsd.unregisterService(listener) } }
    }

    override fun browse(onEvent: (DiscoveryEvent) -> Unit): Closeable {
        val resolver = ResolveQueue(onEvent)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = onEvent(DiscoveryEvent.Failed(errorCode))

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) = resolver.enqueue(serviceInfo)

            override fun onServiceLost(serviceInfo: NsdServiceInfo) =
                onEvent(DiscoveryEvent.Lost(serviceInfo.serviceName, resolver.idOf(serviceInfo.serviceName)))
        }
        nsd.discoverServices(ServiceAdvert.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        return Closeable {
            resolver.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private inner class ResolveQueue(private val onEvent: (DiscoveryEvent) -> Unit) {
        private val lock = Any()
        private val pending = ArrayDeque<NsdServiceInfo>()
        private val ids = HashMap<String, String>()
        private var busy = false
        private var closed = false

        fun idOf(name: String): String? = synchronized(lock) { ids[name] }

        fun enqueue(info: NsdServiceInfo) {
            synchronized(lock) {
                if (closed) return
                pending.addLast(info)
            }
            next()
        }

        fun close() = synchronized(lock) {
            closed = true
            pending.clear()
        }

        private fun next() {
            val info = synchronized(lock) {
                if (busy || closed) return
                pending.pollFirst()?.also { busy = true } ?: return
            }
            @Suppress("DEPRECATION") // ServiceInfoCallback is API 34+; minSdk is 33. resolveService still works on 34+.
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val advert = DiscoveryTxt.decode(serviceInfo.serviceName, serviceInfo.port, serviceInfo.attributes)
                        if (advert != null) {
                            synchronized(lock) { ids[serviceInfo.serviceName] = advert.deviceId }
                            val endpoints = addressesOf(serviceInfo).mapNotNull { address ->
                                address.hostAddress?.substringBefore('%')?.let {
                                    Endpoint.parseOrNull(if (address is Inet6Address) "[$it]:${advert.port}" else "$it:${advert.port}")
                                }
                            }
                            if (endpoints.isNotEmpty()) onEvent(DiscoveryEvent.Found(DiscoveredService(advert, endpoints)))
                        }
                        done()
                    }
                }
            )
        }

        private fun done() {
            synchronized(lock) { busy = false }
            next()
        }
    }

    private fun addressesOf(info: NsdServiceInfo): List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        info.hostAddresses
    } else {
        @Suppress("DEPRECATION")
        listOfNotNull(info.host)
    }
}
