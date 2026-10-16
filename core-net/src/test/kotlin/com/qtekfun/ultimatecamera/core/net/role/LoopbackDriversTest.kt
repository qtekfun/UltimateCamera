package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.clock.SourceClock
import com.qtekfun.ultimatecamera.core.net.control.ClientHandshake
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.HandshakeMode
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.RecordingState
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Camera and monitor drivers over real loopback TCP: pairing with the QR token, then RESUME with the derived key. */
class LoopbackDriversTest {
    @Test
    fun pairThenReconnectWithTheDerivedKeyAndRecord() = runBlocking {
        withTimeout(30_000) {
            val confined = Dispatchers.Default.limitedParallelism(1)
            val clock = { System.nanoTime() }
            val cameraId = LocalIdentity(Secrets.newDeviceId(), "Camera", DeviceRole.CAMERA)
            val monitorId = LocalIdentity(Secrets.newDeviceId(), "Monitor", DeviceRole.MONITOR)
            val token = Secrets.randomBytes(Secrets.KEY_BYTES)

            // Camera side: shows a QR with [token]; stores the pair key of whoever pairs.
            val cameraKeys = ConcurrentHashMap<String, ByteArray>()
            var shownToken: ByteArray? = token
            val keyring = object : Keyring {
                override fun pairKey(deviceId: String) = cameraKeys[deviceId]

                override fun pairingToken() = shownToken

                override fun accepts(role: DeviceRole) = role.isController
            }
            val actions = FakeActions()
            val node = CameraNode("boot", clock, SourceClock(clock, 0), actions)
            val bound = CompletableDeferred<Int>()
            val camera = CameraDriver(
                node = node,
                clock = clock,
                local = cameraId,
                keyring = keyring,
                port = 0,
                onPaired = { session ->
                    cameraKeys[session.peerId] = session.newPairKey!!
                    shownToken = null // a pairing code is used once
                },
                onBound = { bound.complete(it) }
            ).launchIn(this, confined)
            val port = bound.await()

            // Monitor side: PAIR with the token first, RESUME with the stored key afterwards.
            var monitorKey: ByteArray? = null
            val listener = RecordingListener()
            val link = ControllerLink(cameraId.deviceId, clock, listener)
            val dial = endpointDialer({ listOf(Endpoint("127.0.0.1", port)) }) {
                val key = monitorKey
                if (key == null) {
                    ClientHandshake(monitorId, HandshakeMode.PAIR, cameraId.deviceId, token)
                } else {
                    ClientHandshake(monitorId, HandshakeMode.RESUME, cameraId.deviceId, key)
                }
            }
            val monitor = ControllerDriver(link, dial, onSession = { s -> s.newPairKey?.let { monitorKey = it } }, tickMs = 20).launchIn(this, confined)

            suspend fun until(what: String, condition: () -> Boolean) {
                while (!condition()) delay(10)
                assertTrue(what, condition())
            }
            until("connected") { link.state == LinkState.CONNECTED && link.status != null }
            assertTrue(monitorKey!!.contentEquals(cameraKeys[monitorId.deviceId]))

            kotlinx.coroutines.withContext(confined) { link.submit(CommandBody.StartRecording("take-1")) }
            until("recording") { link.status?.recording == RecordingState.RECORDING }

            // The monitor app restarts: a new link and driver, which must RESUME (the token is gone).
            monitor.cancelAndJoin()
            until("camera saw the monitor leave") { node.connectedPeers.isEmpty() }
            assertEquals(RecordingState.RECORDING, node.status.recording) // REQ-NET-007
            val link2 = ControllerLink(cameraId.deviceId, clock, RecordingListener())
            val monitor2 = ControllerDriver(link2, dial, tickMs = 20).launchIn(this, confined)
            until("resumed with state") { link2.state == LinkState.CONNECTED && link2.status?.recording == RecordingState.RECORDING }
            assertEquals(listOf("take-1"), actions.started)

            monitor2.cancelAndJoin()
            camera.cancelAndJoin()
        }
    }
}
