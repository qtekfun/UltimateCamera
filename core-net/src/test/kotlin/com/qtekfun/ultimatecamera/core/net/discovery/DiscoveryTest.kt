package com.qtekfun.ultimatecamera.core.net.discovery

import com.qtekfun.ultimatecamera.core.net.control.ControlProtocol
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory LAN: every advert is visible to every browser, like mDNS on one Wi-Fi network. */
class FakeServiceDiscovery : ServiceDiscovery {
    private val adverts = LinkedHashMap<String, DiscoveredService>()
    private val browsers = mutableListOf<(DiscoveryEvent) -> Unit>()

    fun advertiseAt(advert: ServiceAdvert, endpoints: List<Endpoint>): Closeable {
        // What the platform does: TXT goes out as bytes and comes back decoded.
        val attributes = DiscoveryTxt.encode(advert).mapValues { it.value.toByteArray(Charsets.US_ASCII) }
        val decoded = requireNotNull(DiscoveryTxt.decode(advert.name, advert.port, attributes))
        val service = DiscoveredService(decoded, endpoints)
        adverts[advert.name] = service
        browsers.toList().forEach { it(DiscoveryEvent.Found(service)) }
        return Closeable {
            adverts.remove(advert.name)
            browsers.toList().forEach { it(DiscoveryEvent.Lost(advert.name, null)) }
        }
    }

    override fun advertise(advert: ServiceAdvert, onFailed: (code: Int) -> Unit): Closeable = advertiseAt(advert, listOf(Endpoint("127.0.0.1", advert.port)))

    override fun browse(onEvent: (DiscoveryEvent) -> Unit): Closeable {
        browsers += onEvent
        adverts.values.forEach { onEvent(DiscoveryEvent.Found(it)) }
        return Closeable { browsers -= onEvent }
    }
}

class DiscoveryTest {
    private val camera = ServiceAdvert(Secrets.newDeviceId(), "Cámara A", DeviceRole.CAMERA, ControlProtocol.DEFAULT_PORT, ControlProtocol.VERSION)

    @Test
    fun txtRoundTripsAndIgnoresUnknownKeys() {
        val attributes: MutableMap<String, ByteArray?> = DiscoveryTxt.encode(camera).mapValues { it.value.toByteArray() }.toMutableMap()
        assertEquals(setOf("txtvers", "id", "role", "pv"), attributes.keys)
        attributes["future"] = null
        attributes["extra"] = "x".toByteArray()
        assertEquals(camera, DiscoveryTxt.decode(camera.name, camera.port, attributes))
        assertEquals("_ultimatecamera._tcp", ServiceAdvert.SERVICE_TYPE)
    }

    @Test
    fun foreignOrMalformedAdvertsAreIgnored() {
        val good = DiscoveryTxt.encode(camera).mapValues { it.value.toByteArray() }
        assertNull(DiscoveryTxt.decode("x", 47_802, emptyMap()))
        assertNull(DiscoveryTxt.decode("x", 47_802, good + ("id" to "nope".toByteArray())))
        assertNull(DiscoveryTxt.decode("x", 47_802, good + ("role" to "printer".toByteArray())))
        assertNull(DiscoveryTxt.decode("x", 47_802, good + ("pv" to null)))
        assertNull(DiscoveryTxt.decode("x", 0, good))
        assertNull(DiscoveryTxt.decode(" ", 47_802, good))
    }

    @Test
    fun addressBookPrefersDiscoveredAddressesOfPairedDevicesOnly() {
        val lan = FakeServiceDiscovery()
        val ownId = Secrets.newDeviceId()
        val stranger = camera.copy(deviceId = Secrets.newDeviceId(), name = "Stranger")
        val book = PeerAddressBook(ownId) { it == camera.deviceId }
        val browsing = lan.browse(book::onEvent)
        val stored = listOf(Endpoint("192.168.1.20", 47_802))
        assertEquals(stored, book.endpointsFor(camera.deviceId, stored))

        val advert = lan.advertiseAt(camera, listOf(Endpoint("192.168.1.44", 47_802), Endpoint("192.168.1.20", 47_802)))
        lan.advertiseAt(stranger, listOf(Endpoint("192.168.1.99", 47_802)))
        lan.advertiseAt(camera.copy(deviceId = ownId, name = "Me"), listOf(Endpoint("192.168.1.2", 47_802)))
        assertTrue(book.isOnline(camera.deviceId))
        assertFalse(book.isOnline(stranger.deviceId))
        assertFalse(book.isOnline(ownId))
        assertEquals(listOf(Endpoint("192.168.1.44", 47_802), Endpoint("192.168.1.20", 47_802)), book.endpointsFor(camera.deviceId, stored))

        advert.close() // camera leaves the network: lost by name only, as NsdManager reports it
        assertFalse(book.isOnline(camera.deviceId))
        assertEquals(stored, book.endpointsFor(camera.deviceId, stored))
        browsing.close()
    }
}
