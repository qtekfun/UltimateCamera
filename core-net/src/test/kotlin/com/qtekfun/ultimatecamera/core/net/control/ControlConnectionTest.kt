package com.qtekfun.ultimatecamera.core.net.control

import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
}
