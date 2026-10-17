package com.qtekfun.ultimatecamera.core.net.control

import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.security.ProviderException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlConnectionTest {
    private val camera = LocalIdentity(Secrets.newDeviceId(), "Camera", DeviceRole.CAMERA)
    private val monitor = LocalIdentity(Secrets.newDeviceId(), "Monitor", DeviceRole.MONITOR)
    private val pairKey = Secrets.randomBytes(Secrets.KEY_BYTES)

    private val keyring = object : Keyring {
        override fun pairKey(deviceId: String) = if (deviceId == monitor.deviceId) pairKey else null

        override fun pairingToken(): ByteArray? = null
    }

    @Test
    fun handshakeAndEncryptedMessagesOverLoopbackTcp() = runBlocking {
        withTimeout(20_000) {
            val bound = CompletableDeferred<Int>()
            val serverSide = CompletableFuture<ControlConnection>()
            val rejected = CompletableFuture<Exception>()
            val server = launch(Dispatchers.IO) {
                ControlSockets.serve(
                    port = 0,
                    handshakeFactory = { ServerHandshake(camera, keyring) },
                    onSession = { serverSide.complete(it) },
                    onRejected = { _, e -> rejected.complete(e) },
                    onBound = { bound.complete(it) }
                )
            }
            val port = bound.await()
            val client = ControlSockets.connect("127.0.0.1", port, ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey))
            val peer = withContext(Dispatchers.IO) { serverSide.get(10, TimeUnit.SECONDS) }
            assertEquals(monitor.deviceId, peer.session.peerId)
            assertEquals(camera.deviceId, client.session.peerId)

            val command = ControlMessage.Command("c1", CommandBody.StartRecording("take"))
            withContext(Dispatchers.IO) {
                client.send(command)
                assertEquals(Decoded.Message(command), peer.receive())
                peer.send(ControlMessage.Ack("c1"))
                assertEquals(Decoded.Message(ControlMessage.Ack("c1")), client.receive())
                // Many messages in a row keep the frame counters in step.
                repeat(200) { client.send(ControlMessage.Heartbeat(it.toLong())) }
                repeat(200) { assertEquals(Decoded.Message(ControlMessage.Heartbeat(it.toLong())), peer.receive()) }
                client.close()
                assertNull(peer.receive()) // clean end of stream
                peer.close()
            }

            // A device without a pair key is refused, and the server keeps serving.
            val stranger = LocalIdentity(Secrets.newDeviceId(), "Stranger", DeviceRole.MONITOR)
            val refusal = runCatching {
                ControlSockets.connect("127.0.0.1", port, ClientHandshake(stranger, HandshakeMode.RESUME, camera.deviceId, pairKey))
            }.exceptionOrNull()
            assertTrue("$refusal", refusal is HandshakeException && refusal.code == RejectCode.UNKNOWN_DEVICE)
            val serverError = withContext(Dispatchers.IO) { rejected.get(10, TimeUnit.SECONDS) }
            assertTrue(serverError is HandshakeException)
            server.cancelAndJoin()
        }
    }

    /**
     * Regression (D77): a keyring that throws something other than IOException/HandshakeException (a Keystore
     * ProviderException behind the pair keys), or an onSession that throws (storing the new pair key failed), escaped
     * `serve`, ended the listener and, in the pairing screen's viewModelScope, crashed the app.
     */
    @Test
    fun unexpectedKeyringOrSessionFailuresDropOnlyThatPeer() = runBlocking {
        withTimeout(20_000) {
            val keyringCalls = AtomicInteger()
            val flakyKeyring = object : Keyring {
                override fun pairKey(deviceId: String): ByteArray? {
                    if (keyringCalls.getAndIncrement() == 0) throw ProviderException("Keystore operation failed")
                    return if (deviceId == monitor.deviceId) pairKey else null
                }

                override fun pairingToken(): ByteArray? = null
            }
            val bound = CompletableDeferred<Int>()
            val rejected = LinkedBlockingQueue<Exception>()
            val sessions = AtomicInteger()
            val accepted = CompletableFuture<ControlConnection>()
            val server = launch(Dispatchers.IO) {
                ControlSockets.serve(
                    port = 0,
                    handshakeFactory = { ServerHandshake(camera, flakyKeyring) },
                    onSession = {
                        check(sessions.getAndIncrement() != 0) { "could not store the pair key" }
                        accepted.complete(it)
                    },
                    onRejected = { _, e -> rejected.put(e) },
                    onBound = { bound.complete(it) }
                )
            }
            val port = bound.await()
            suspend fun dial() = runCatching {
                ControlSockets.connect("127.0.0.1", port, ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey))
            }
            // 1: the keyring throws while the server builds its hello; the client sees the connection close.
            assertTrue(dial().isFailure)
            assertTrue(withContext(Dispatchers.IO) { rejected.poll(10, TimeUnit.SECONDS) } is ProviderException)
            // 2: the handshake completes, onSession throws; that connection is closed and reported.
            val dropped = dial().getOrNull()
            assertTrue(withContext(Dispatchers.IO) { rejected.poll(10, TimeUnit.SECONDS) } is IllegalStateException)
            dropped?.close()
            // 3: the listener is still there and a normal session works.
            val client = dial().getOrThrow()
            assertEquals(monitor.deviceId, withContext(Dispatchers.IO) { accepted.get(10, TimeUnit.SECONDS) }.session.peerId)
            client.close()
            server.cancelAndJoin()
        }
    }
}
