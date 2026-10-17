package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.discovery.DiscoveredService
import com.qtekfun.ultimatecamera.core.net.discovery.DiscoveryEvent
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceAdvert
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.PairingParseResult
import com.qtekfun.ultimatecamera.core.net.pairing.PairingPayload
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortCodeTest {
    @Test
    fun generatesEightDigits() {
        repeat(50) {
            val code = ShortCode.generate()
            assertEquals(8, code.length)
            assertTrue(code.all(Char::isDigit))
        }
    }

    @Test
    fun normalizeAcceptsSpacesAndDashesOnly() {
        assertEquals("01234567", ShortCode.normalize("0123 4567"))
        assertEquals("01234567", ShortCode.normalize("0123-4567"))
        assertNull(ShortCode.normalize("1234567"))
        assertNull(ShortCode.normalize("1234567a"))
        assertNull(ShortCode.normalize("123456789"))
    }

    @Test
    fun formatsInTwoGroups() {
        assertEquals("0123 4567", ShortCode.format("01234567"))
    }

    @Test
    fun tokenIsDeterministicAndBoundToDeviceAndCode() {
        val id = Secrets.newDeviceId()
        val token = ShortCode.token(id, "12345678")
        assertEquals(Secrets.KEY_BYTES, token.size)
        assertArrayEquals(token, ShortCode.token(id, "12345678"))
        assertFalse(token.contentEquals(ShortCode.token(id, "12345679")))
        assertFalse(token.contentEquals(ShortCode.token(Secrets.newDeviceId(), "12345678")))
    }
}

class PairingWindowTest {
    private var now = 1_000_000L
    private val window = PairingWindow { now }
    private val token = ByteArray(32) { it.toByte() }

    @Test
    fun tokenIsGivenOnceThenTheWindowIsUsed() {
        window.open("12345678", token)
        assertArrayEquals(token, window.takeToken())
        assertEquals(WindowState.Used, window.state.value)
        assertNull(window.takeToken())
    }

    @Test
    fun noTokenAfterExpiry() {
        window.open("12345678", token, lifetimeMs = 1_000)
        now += 1_001
        assertNull(window.takeToken())
        assertEquals(WindowState.Closed, window.state.value)
    }

    @Test
    fun expireIfDueClosesOnlyAnExpiredCode() {
        window.open("12345678", token, lifetimeMs = 1_000)
        assertFalse(window.expireIfDue())
        now += 2_000
        assertTrue(window.expireIfDue())
        assertNull(window.takeToken())
    }

    @Test
    fun closedWindowGivesNothing() {
        window.open("12345678", token)
        window.close()
        assertNull(window.takeToken())
    }
}

class DiscoveryStatusTest {
    private val own = Secrets.newDeviceId()
    private val cam = Secrets.newDeviceId()

    private fun found(name: String, id: String, port: Int, host: String = "192.168.1.20") =
        DiscoveryEvent.Found(DiscoveredService(ServiceAdvert(id, name, DeviceRole.CAMERA, port, 1), listOf(Endpoint(host, port))))

    @Test
    fun mergesAdvertsOfOnePhoneAndHidesThisPhone() {
        val status = DiscoveryStatus()
            .reduce(found("Pixel", cam, 47_802))
            .reduce(found("Pixel (2)", cam, 40_000))
            .reduce(found("Me", own, 47_802, "192.168.1.30"))
        val devices = status.devices(own)
        assertEquals(1, devices.size)
        assertEquals("Pixel", devices[0].name)
        assertEquals(setOf(47_802, 40_000), devices[0].endpoints.map { it.port }.toSet())
        assertTrue(status.isOnline(cam))
    }

    @Test
    fun lostRemovesOnlyThatAdvert() {
        val status = DiscoveryStatus()
            .reduce(found("Pixel", cam, 47_802))
            .reduce(found("Pixel (2)", cam, 40_000))
            .reduce(DiscoveryEvent.Lost("Pixel (2)", null))
        assertEquals(listOf(47_802), status.devices(own).single().endpoints.map { it.port })
        assertFalse(status.reduce(DiscoveryEvent.Lost("Pixel", cam)).isOnline(cam))
    }

    @Test
    fun failureIsReported() {
        val status = DiscoveryStatus(state = DiscoveryStatus.State.SEARCHING).reduce(DiscoveryEvent.Failed(3))
        assertEquals(DiscoveryStatus.State.FAILED, status.state)
        assertEquals(3, status.failureCode)
    }
}

class LocalEndpointsTest {
    @Test
    fun wifiFirstThenHotspotAndNoLoopbackOrMobile() {
        val picked = LocalEndpoints.pick(
            listOf(
                LocalEndpoints.Candidate("rmnet0", "10.0.0.5"),
                LocalEndpoints.Candidate("lo", "127.0.0.1"),
                LocalEndpoints.Candidate("swlan0", "192.168.43.1"),
                LocalEndpoints.Candidate("wlan0", "192.168.1.20"),
                LocalEndpoints.Candidate("wlan1", "169.254.3.3")
            ),
            port = 40_000
        )
        assertEquals(listOf("192.168.1.20", "192.168.43.1"), picked.map { it.host })
        assertTrue(picked.all { it.port == 40_000 })
    }

    @Test
    fun atMostFourEndpoints() {
        val many = (1..8).map { LocalEndpoints.Candidate("wlan$it", "192.168.$it.2") }
        assertEquals(PairingPayload.MAX_ENDPOINTS, LocalEndpoints.pick(many, 1).size)
    }
}

class LastSeenTest {
    @Test
    fun buckets() {
        val now = 10L * 24 * 3_600_000
        assertEquals(LastSeen.Never, LastSeen.of(null, now))
        assertEquals(LastSeen.JustNow, LastSeen.of(now - 59_000, now))
        assertEquals(LastSeen.Minutes(5), LastSeen.of(now - 5 * 60_000, now))
        assertEquals(LastSeen.Hours(3), LastSeen.of(now - 3 * 3_600_000, now))
        assertEquals(LastSeen.Days(2), LastSeen.of(now - 2 * 24 * 3_600_000, now))
        assertEquals(LastSeen.JustNow, LastSeen.of(now + 5_000, now)) // clock moved back
    }
}

class QrCodesTest {
    @Test
    fun aPairingPayloadSurvivesEncodeRenderDecode() {
        val payload = PairingPayload(
            deviceId = Secrets.newDeviceId(),
            name = "Pixel 8 cámara",
            role = DeviceRole.CAMERA,
            endpoints = listOf(Endpoint("192.168.1.20", 40_123), Endpoint("192.168.43.1", 40_123)),
            token = ShortCode.token(Secrets.newDeviceId(), "12345678"),
            expiresAtEpochSeconds = 2_000_000_000L
        )
        val text = payload.encode()
        val matrix = QrCodes.encode(text)
        // Render 4 px per module into a luminance plane with a wider row stride, like a camera Y plane.
        val scale = 4
        val width = matrix.width * scale
        val height = matrix.height * scale
        val stride = width + 16
        val plane = ByteArray(stride * height) { 0xFF.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matrix.get(x / scale, y / scale)) plane[y * stride + x] = 0
            }
        }
        val decoded = QrCodes.decode(plane, width, height, stride)
        assertEquals(text, decoded)
        val parsed = PairingPayload.parse(decoded!!, nowEpochMs = null) as PairingParseResult.Ok
        assertEquals(payload, parsed.payload)
    }

    @Test
    fun aBlankFrameHasNoCode() {
        assertNull(QrCodes.decode(ByteArray(64 * 48) { 0x80.toByte() }, 64, 48))
    }
}
