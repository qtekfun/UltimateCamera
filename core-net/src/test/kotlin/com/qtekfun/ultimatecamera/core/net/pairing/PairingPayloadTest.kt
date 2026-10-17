package com.qtekfun.ultimatecamera.core.net.pairing

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPayloadTest {
    private val now = 1_790_000_000_000L
    private val id = Secrets.newDeviceId()
    private val endpoints = listOf(Endpoint("192.168.1.20", 47_802), Endpoint("fe80::1c2b:3aff:fe4d:5e6f", 47_802), Endpoint("pixel-8.local", 47_802))

    private fun payload(name: String = "Cámara A (Pixel 8)") = PairingPayload.create(id, name, DeviceRole.CAMERA, endpoints, now)

    private fun ok(text: String, at: Long? = now, own: String? = null): PairingPayload {
        val result = PairingPayload.parse(text, at, own)
        assertTrue("expected Ok, got $result", result is PairingParseResult.Ok)
        return (result as PairingParseResult.Ok).payload
    }

    private fun error(text: String, at: Long? = now, own: String? = null): PairingError {
        val result = PairingPayload.parse(text, at, own)
        assertTrue("expected Invalid, got $result", result is PairingParseResult.Invalid)
        return (result as PairingParseResult.Invalid).error
    }

    @Test
    fun roundTripsWithNonAsciiNameAndIpv6() {
        val original = payload()
        val text = original.encode()
        assertTrue(text.startsWith("ultimatecamera://pair?v=1&id="))
        assertEquals(original, ok(text))
        assertEquals(original.expiresAtEpochSeconds, now / 1_000 + PairingPayload.DEFAULT_LIFETIME_SECONDS)
    }

    @Test
    fun isCompactEnoughForASmallQrCode() {
        val text = payload("A".repeat(PairingPayload.MAX_NAME_CHARS)).encode()
        // 3 endpoints incl. a link-local IPv6 and a 40-char name: still well inside a version 10 QR code (≈ 213 bytes at M).
        assertTrue("${text.length} chars: $text", text.length <= 260)
    }

    @Test
    fun tokenIsFreshEachTimeAndHiddenFromToString() {
        val a = payload()
        val b = payload()
        assertFalse(a.token.contentEquals(b.token))
        assertFalse(a.toString().contains(B64.encode(a.token)))
    }

    @Test
    fun refusesOtherCodesAndNewerVersions() {
        assertEquals(PairingError.NOT_A_PAIRING_CODE, error("https://example.com/?v=1"))
        assertEquals(PairingError.NOT_A_PAIRING_CODE, error(""))
        val text = payload().encode()
        assertEquals(PairingError.UNSUPPORTED_VERSION, error(text.replace("v=1&", "v=2&")))
        assertEquals(PairingError.MALFORMED, error(text.replace("v=1&", "v=0&")))
    }

    @Test
    fun refusesMalformedFields() {
        val text = payload().encode()
        val token = B64.encode(payload().token)
        val cases = listOf(
            text.replace("&id=$id", "&id=short"),
            text.replace("&r=camera", "&r=director"),
            text.replace(Regex("&t=[^&]+"), "&t=${token.dropLast(4)}"),
            text.replace(Regex("&h=[^&]+"), "&h=192.168.1.20:99999"),
            text.replace(Regex("&h=[^&]+"), "&h=300.1.1.1:47802"),
            text.replace(Regex("&h=[^&]+"), "&h=bad_host!:47802"),
            text.replace(Regex("&h=[^&]+"), "&h=" + List(5) { "10.0.0.$it:1" }.joinToString(",")),
            text.replace(Regex("&x=[^&]+"), "&x=soon"),
            text.replace(Regex("&n=[^&]+"), "&n="),
            "$text&id=$id",
            text.replace(Regex("&t=[^&]+"), "")
        )
        for (case in cases) assertEquals(case, PairingError.MALFORMED, error(case))
    }

    @Test
    fun ignoresUnknownParametersForForwardCompatibility() {
        val original = payload()
        assertEquals(original, ok(original.encode() + "&wd=02:00:00:00:00:00&future=1"))
    }

    @Test
    fun expiryAndOwnCode() {
        val original = payload()
        val text = original.encode()
        assertNotNull(ok(text, at = (original.expiresAtEpochSeconds) * 1_000))
        assertEquals(PairingError.EXPIRED, error(text, at = (original.expiresAtEpochSeconds + 1) * 1_000))
        assertNotNull(ok(text, at = null))
        assertEquals(PairingError.SELF, error(text, own = id))
    }

    @Test
    fun endpointParsing() {
        assertEquals(Endpoint("10.0.0.2", 80), Endpoint.parseOrNull("10.0.0.2:80"))
        assertEquals(Endpoint("fe80::1", 80), Endpoint.parseOrNull("[fe80::1]:80"))
        assertEquals("[fe80::1]:80", Endpoint("fe80::1", 80).toString())
        assertNull(Endpoint.parseOrNull("fe80::1:80"))
        assertNull(Endpoint.parseOrNull("host"))
        assertNull(Endpoint.parseOrNull("host:0"))
        assertNull(Endpoint.parseOrNull(":80"))
        assertTrue(PairingPayload.isValidHost("my-phone.local"))
        assertFalse(PairingPayload.isValidHost("-bad.local"))
        assertFalse(PairingPayload.isValidHost("1.2.3"))
    }
}
