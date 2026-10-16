package com.qtekfun.ultimatecamera.core.net.control

import com.qtekfun.ultimatecamera.core.net.pairing.B64
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HandshakeTest {
    private val camera = LocalIdentity(Secrets.newDeviceId(), "Cámara A", DeviceRole.CAMERA)
    private val monitor = LocalIdentity(Secrets.newDeviceId(), "Monitor", DeviceRole.MONITOR)
    private val pairKey = Secrets.randomBytes(Secrets.KEY_BYTES)
    private val token = Secrets.randomBytes(Secrets.KEY_BYTES)

    private inner class TestKeyring(var token: ByteArray? = null, val keys: Map<String, ByteArray> = emptyMap()) : Keyring {
        override fun pairKey(deviceId: String) = keys[deviceId]

        override fun pairingToken() = token

        override fun accepts(role: DeviceRole) = role.isController
    }

    private fun run(client: ClientHandshake, server: ServerHandshake): Pair<Session, Session> {
        val reply = server.onClientHello(HandshakeCodec.decode(HandshakeCodec.encode(client.hello())))
        val (finish, clientSession) = client.onServerMessage(HandshakeCodec.decode(HandshakeCodec.encode(reply)))
        val serverSession = server.onClientFinish(HandshakeCodec.decode(HandshakeCodec.encode(finish)))
        return clientSession to serverSession
    }

    private fun assertRejected(expected: RejectCode, block: () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: HandshakeException) {
            assertEquals(expected, e.code)
        }
    }

    @Test
    fun resumeAuthenticatesBothSidesAndAgreesOnKeys() {
        val (c, s) = run(
            ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey),
            ServerHandshake(camera, TestKeyring(keys = mapOf(monitor.deviceId to pairKey)))
        )
        assertEquals(camera.deviceId, c.peerId)
        assertEquals(DeviceRole.CAMERA, c.peerRole)
        assertEquals(monitor.deviceId, s.peerId)
        assertEquals("Monitor", s.peerName)
        assertArrayEquals(c.sendKey, s.receiveKey)
        assertArrayEquals(s.sendKey, c.receiveKey)
        assertFalse(c.sendKey.contentEquals(c.receiveKey))
        assertArrayEquals(c.clockKey, s.clockKey)
        assertNull(c.newPairKey)
        assertEquals(ControlProtocol.VERSION, c.protocol)
    }

    @Test
    fun everySessionHasFreshKeys() {
        val keyring = TestKeyring(keys = mapOf(monitor.deviceId to pairKey))
        val (a, _) = run(ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey), ServerHandshake(camera, keyring))
        val (b, _) = run(ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey), ServerHandshake(camera, keyring))
        assertFalse(a.sendKey.contentEquals(b.sendKey))
    }

    @Test
    fun pairingDerivesTheSameNewPairKeyOnBothSides() {
        val (c, s) = run(ClientHandshake(monitor, HandshakeMode.PAIR, camera.deviceId, token), ServerHandshake(camera, TestKeyring(token = token)))
        val key = requireNotNull(c.newPairKey)
        assertArrayEquals(key, s.newPairKey)
        assertEquals(Secrets.KEY_BYTES, key.size)
        assertFalse(key.contentEquals(token)) // the QR token is not the long-term key
        // The new key then works for RESUME.
        run(ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, key), ServerHandshake(camera, TestKeyring(keys = mapOf(monitor.deviceId to key))))
    }

    @Test
    fun wrongKeyIsRefusedOnBothSides() {
        // Client with a wrong key: the server's proof does not verify at the client.
        assertRejected(RejectCode.BAD_PROOF) {
            run(
                ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, Secrets.randomBytes(32)),
                ServerHandshake(camera, TestKeyring(keys = mapOf(monitor.deviceId to pairKey)))
            )
        }
        // A forged finish (attacker who cannot compute the proof) is refused by the server.
        val server = ServerHandshake(camera, TestKeyring(keys = mapOf(monitor.deviceId to pairKey)))
        server.onClientHello(ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey).hello())
        assertRejected(RejectCode.BAD_PROOF) { server.onClientFinish(HandshakeMessage.ClientFinish(B64.encode(ByteArray(32)))) }
    }

    @Test
    fun tamperedServerHelloIsDetected() {
        val client = ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey)
        val reply = ServerHandshake(
            camera,
            TestKeyring(keys = mapOf(monitor.deviceId to pairKey))
        ).onClientHello(client.hello()) as HandshakeMessage.ServerHello
        assertRejected(RejectCode.BAD_PROOF) { client.onServerMessage(reply.copy(role = DeviceRole.MASTER)) }
    }

    @Test
    fun serverRefusals() {
        fun reply(keyring: Keyring, hello: HandshakeMessage.ClientHello) = ServerHandshake(camera, keyring).onClientHello(hello)
        val resume = ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey).hello()
        val known = TestKeyring(keys = mapOf(monitor.deviceId to pairKey))
        assertEquals(HandshakeMessage.Reject(RejectCode.UNKNOWN_DEVICE), reply(TestKeyring(), resume))
        assertEquals(HandshakeMessage.Reject(RejectCode.PAIRING_CLOSED), reply(TestKeyring(), resume.copy(mode = HandshakeMode.PAIR)))
        assertEquals(HandshakeMessage.Reject(RejectCode.VERSION), reply(known, resume.copy(protocol = 99, minProtocol = 99)))
        assertEquals(HandshakeMessage.Reject(RejectCode.ROLE_NOT_ALLOWED), reply(known, resume.copy(role = DeviceRole.CAMERA)))
        assertEquals(HandshakeMessage.Reject(RejectCode.PROTOCOL), reply(known, resume.copy(target = Secrets.newDeviceId())))
        assertEquals(HandshakeMessage.Reject(RejectCode.PROTOCOL), reply(known, resume.copy(key = B64.encode(ByteArray(10)))))
        assertEquals(HandshakeMessage.Reject(RejectCode.PROTOCOL), reply(known, resume.copy(nonce = "short")))
        // A newer client that still speaks our version is served at our version.
        val newer = reply(known, resume.copy(protocol = ControlProtocol.VERSION + 3))
        assertEquals(ControlProtocol.VERSION, (newer as HandshakeMessage.ServerHello).protocol)
    }

    @Test
    fun clientRefusesTheWrongDeviceAndRejects() {
        val client = ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey)
        val other = LocalIdentity(Secrets.newDeviceId(), "Other", DeviceRole.CAMERA)
        val hello = client.hello().copy(target = other.deviceId)
        val reply = ServerHandshake(other, TestKeyring(keys = mapOf(monitor.deviceId to pairKey))).onClientHello(hello)
        assertRejected(RejectCode.BAD_PROOF) { client.onServerMessage(reply) }
        assertRejected(RejectCode.UNKNOWN_DEVICE) { client.onServerMessage(HandshakeMessage.Reject(RejectCode.UNKNOWN_DEVICE)) }
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val prk = HandshakeCrypto.hkdfExtract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.toHex())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            HandshakeCrypto.hkdfExpand(prk, info, 42).toHex()
        )
    }

    @Test
    fun framesAreAuthenticatedAndInOrder() {
        val key = Secrets.randomBytes(32)
        val sender = FrameCipher(key)
        val receiver = FrameCipher(key)
        val first = sender.seal("one".toByteArray())
        val second = sender.seal("two".toByteArray())
        assertEquals("one", receiver.open(first).toString(Charsets.UTF_8))
        // Replay of the first frame fails: the receiver expects counter 1.
        assertTrue(runCatching { FrameCipher(key).also { it.open(first) }.open(first) }.exceptionOrNull() is IOException)
        val tampered = second.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue(runCatching { receiver.open(tampered) }.exceptionOrNull() is IOException)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
