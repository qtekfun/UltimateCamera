package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.clock.SourceClock
import com.qtekfun.ultimatecamera.core.net.control.ClientHandshake
import com.qtekfun.ultimatecamera.core.net.control.ControlFeatures
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import com.qtekfun.ultimatecamera.core.net.control.ControlSockets
import com.qtekfun.ultimatecamera.core.net.control.Decoded
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.HandshakeException
import com.qtekfun.ultimatecamera.core.net.control.HandshakeMode
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.RejectCode
import com.qtekfun.ultimatecamera.core.net.monitor.MonitorRole
import com.qtekfun.ultimatecamera.core.net.monitor.QueueSignallingTransport
import com.qtekfun.ultimatecamera.core.net.monitor.SignallingCodec
import com.qtekfun.ultimatecamera.core.net.monitor.SignallingMessage
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** WebRTC signalling inside the authenticated control channel (D70). */
class ControlSignallingTest {
    private class Recorder : SignalListener {
        val signals = mutableListOf<Pair<String, SignallingMessage>>()
        val gone = mutableListOf<String>()

        override fun onSignal(peerId: String, message: SignallingMessage) {
            signals += peerId to message
        }

        override fun onPeerGone(peerId: String) {
            gone += peerId
        }
    }

    @Test
    fun featuresAreExchangedAndSignalsFlowBothWays() {
        val clock = FakeClock()
        val recorder = Recorder()
        val camera = CameraNode("boot", clock, FakeCommonClock(), FakeActions(), signals = recorder)
        val received = mutableListOf<SignallingMessage>()
        val link = ControllerLink(
            "camera",
            clock,
            object : ControllerListener {
                override fun onSignal(message: SignallingMessage) {
                    received += message
                }
            }
        )
        val toCamera = FakeSink()
        val toController = FakeSink()
        link.start()
        assertTrue(link.pollDial())
        camera.onPeerConnected("monitor", DeviceRole.MONITOR, toController)
        link.onConnected(toCamera)
        assertTrue(toController.queue.first() is ControlMessage.Features)
        assertTrue(toCamera.queue.first() is ControlMessage.Features)
        assertFalse(link.sendSignal(SignallingMessage.Hello(1, MonitorRole.MONITOR))) // features not known yet

        fun pump() {
            while (toCamera.queue.isNotEmpty() || toController.queue.isNotEmpty()) {
                while (toCamera.queue.isNotEmpty()) camera.onMessage("monitor", Decoded.Message(toCamera.queue.removeFirst()))
                while (toController.queue.isNotEmpty()) link.onMessage(Decoded.Message(toController.queue.removeFirst()))
            }
        }
        pump()
        assertEquals(ControlFeatures.LOCAL, link.cameraFeatures)
        assertEquals(setOf("monitor"), camera.signalling.signallingPeers)
        assertTrue(camera.signalling.legacyPeers.isEmpty())

        assertTrue(link.sendSignal(SignallingMessage.Hello(1, MonitorRole.MONITOR)))
        pump()
        assertEquals(listOf("monitor" to SignallingMessage.Hello(1, MonitorRole.MONITOR)), recorder.signals)
        assertTrue(camera.signalling.send("monitor", SignallingMessage.Offer("v=0 offer")))
        pump()
        assertEquals(listOf<SignallingMessage>(SignallingMessage.Offer("v=0 offer")), received)

        camera.onPeerDisconnected("monitor", toController)
        assertEquals(listOf("monitor"), recorder.gone)
        assertFalse(camera.signalling.send("monitor", SignallingMessage.Offer("late")))
    }

    @Test
    fun aPeerWithoutTheFeatureIsLegacyAndItsSignalsAreIgnored() {
        val recorder = Recorder()
        val camera = CameraNode("boot", FakeClock(), FakeCommonClock(), FakeActions(), signals = recorder)
        val sink = FakeSink()
        camera.onPeerConnected("old-monitor", DeviceRole.MONITOR, sink)
        assertTrue(camera.signalling.legacyPeers.isEmpty()) // unknown until its first message
        camera.onMessage("old-monitor", Decoded.Message(ControlMessage.Heartbeat(1)))
        assertEquals(setOf("old-monitor"), camera.signalling.legacyPeers)
        camera.onMessage("old-monitor", Decoded.Message(ControlMessage.Signal(SignallingMessage.Hello(1, MonitorRole.MONITOR))))
        assertTrue(recorder.signals.isEmpty())
        assertFalse(camera.signalling.send("old-monitor", SignallingMessage.Offer("x")))
    }

    @Test
    fun controllerRoutesByTheCameraFeatures() {
        val clock = FakeClock()
        val link = ControllerLink("camera", clock, object : ControllerListener {})
        val signalling = ControllerSignalling(link) { it() }
        assertEquals(VideoRoute.None, signalling.route("10.0.0.2"))
        link.start()
        link.pollDial()
        link.onConnected(FakeSink())
        assertEquals(VideoRoute.None, signalling.route("10.0.0.2")) // features not known yet
        link.onMessage(Decoded.Message(ControlMessage.Heartbeat(1))) // an older camera opens with something else
        assertEquals(VideoRoute.Legacy("10.0.0.2"), signalling.route("10.0.0.2"))

        lateinit var signalling2: ControllerSignalling
        val link2 = ControllerLink(
            "camera",
            clock,
            object : ControllerListener {
                override fun onLinkState(state: LinkState) = signalling2.onLinkState(state)
            }
        )
        signalling2 = ControllerSignalling(link2) { it() }
        link2.start()
        link2.pollDial()
        link2.onConnected(FakeSink())
        link2.onMessage(Decoded.Message(ControlMessage.Features(ControlFeatures.LOCAL)))
        val route = signalling2.route("10.0.0.2") as VideoRoute.Control
        signalling2.onSignal(SignallingMessage.Offer("o"))
        assertEquals(SignallingMessage.Offer("o"), route.transport.receive())
        link2.onDisconnected()
        assertTrue(route.transport.closed.value)
        assertNull(route.transport.receive())
    }

    @Test
    fun hubGivesEachHelloItsOwnSessionAndEndsTheOldOne() {
        val sent = mutableListOf<Pair<String, SignallingMessage>>()
        val hub = VideoSignalHub { peer, m -> sent.add(peer to m) }
        hub.onSignal("m", SignallingMessage.Ice(null, 0, "stray")) // no session: dropped
        hub.onSignal("m", SignallingMessage.Hello(1, MonitorRole.MONITOR))
        val first = hub.requests.tryReceive().getOrThrow()
        assertEquals(SignallingMessage.Hello(1, MonitorRole.MONITOR), first.transport.receive())
        first.transport.send(SignallingMessage.Offer("o"))
        assertEquals(listOf("m" to SignallingMessage.Offer("o")), sent)
        hub.onSignal("m", SignallingMessage.Hello(1, MonitorRole.MONITOR))
        assertTrue(first.transport.closed.value)
        val second = hub.requests.tryReceive().getOrThrow()
        hub.onPeerGone("m")
        assertTrue(second.transport.closed.value)
        try {
            second.transport.send(SignallingMessage.Answer("a"))
            fail("closed transport sent")
        } catch (_: IOException) {
            // expected
        }
    }

    /** Real loopback TCP: a paired monitor completes offer/answer/ICE over the control channel; no media needed. */
    @Test
    fun pairedMonitorCompletesSignallingAndAnUnpairedPeerGetsNoOffer() = runBlocking {
        withTimeout(30_000) {
            val confined = Dispatchers.Default.limitedParallelism(1)
            val clock = { System.nanoTime() }
            val cameraId = LocalIdentity(Secrets.newDeviceId(), "Camera", DeviceRole.CAMERA)
            val monitorId = LocalIdentity(Secrets.newDeviceId(), "Monitor", DeviceRole.MONITOR)
            val pairKey = Secrets.randomBytes(Secrets.KEY_BYTES)
            val keys = ConcurrentHashMap(mapOf(monitorId.deviceId to pairKey))
            val keyring = object : Keyring {
                override fun pairKey(deviceId: String) = keys[deviceId]

                override fun pairingToken(): ByteArray? = null

                override fun accepts(role: DeviceRole) = role.isController
            }
            lateinit var node: CameraNode
            val hub = VideoSignalHub { peer, m ->
                launch(confined) { node.signalling.send(peer, m) }
                true
            }
            node = withContext(confined) { CameraNode("boot", clock, SourceClock(clock, 0), FakeActions(), signals = hub) }
            val bound = CompletableDeferred<Int>()
            val camera = CameraDriver(node, clock, cameraId, keyring, port = 0, onBound = { bound.complete(it) }).launchIn(this, confined)
            val port = bound.await()

            strangerIsRefused(port, cameraId, monitorId)
            assertTrue("no video request from strangers", hub.requests.tryReceive().isFailure)
            assertTrue(withContext(confined) { node.connectedPeers.isEmpty() && node.signalling.legacyPeers.isEmpty() })

            // The paired monitor.
            lateinit var signalling: ControllerSignalling
            val link = ControllerLink(
                cameraId.deviceId,
                clock,
                object : ControllerListener {
                    override fun onSignal(message: SignallingMessage) = signalling.onSignal(message)

                    override fun onLinkState(state: LinkState) = signalling.onLinkState(state)
                }
            )
            signalling = ControllerSignalling(link) { block -> launch(confined) { block() } }
            val dial = endpointDialer({ listOf(Endpoint("127.0.0.1", port)) }) { ClientHandshake(monitorId, HandshakeMode.RESUME, cameraId.deviceId, pairKey) }
            val monitor = ControllerDriver(link, dial, tickMs = 20).launchIn(this, confined)
            var route: VideoRoute = VideoRoute.None
            while (route !is VideoRoute.Control) {
                delay(20)
                route = withContext(confined) { signalling.route(legacyHost = "127.0.0.1") }
            }
            val monitorSide = route.transport

            // Camera: a stand-in for MonitorSender that answers the hello with its hello and an offer.
            val cameraRun = launch(Dispatchers.IO) {
                val request = hub.requests.receive()
                assertEquals(monitorId.deviceId, request.peerId)
                val t: QueueSignallingTransport = request.transport
                assertEquals(SignallingMessage.Hello(SignallingCodec.PROTOCOL_VERSION, MonitorRole.MONITOR), t.receive())
                t.send(SignallingMessage.Hello(SignallingCodec.PROTOCOL_VERSION, MonitorRole.CAMERA))
                t.send(SignallingMessage.Offer("v=0 camera-offer"))
                t.send(SignallingMessage.Ice("0", 0, "candidate:camera"))
                assertEquals(SignallingMessage.Answer("v=0 monitor-answer"), t.receive())
                assertEquals(SignallingMessage.Ice("0", 0, "candidate:monitor"), t.receive())
            }
            withContext(Dispatchers.IO) {
                monitorSide.send(SignallingMessage.Hello(SignallingCodec.PROTOCOL_VERSION, MonitorRole.MONITOR))
                assertEquals(SignallingMessage.Hello(SignallingCodec.PROTOCOL_VERSION, MonitorRole.CAMERA), monitorSide.receive())
                assertEquals(SignallingMessage.Offer("v=0 camera-offer"), monitorSide.receive())
                assertEquals(SignallingMessage.Ice("0", 0, "candidate:camera"), monitorSide.receive())
                monitorSide.send(SignallingMessage.Answer("v=0 monitor-answer"))
                monitorSide.send(SignallingMessage.Ice("0", 0, "candidate:monitor"))
            }
            cameraRun.join()
            assertTrue(withContext(confined) { node.signalling.legacyPeers.isEmpty() }) // no legacy socket needed

            monitor.cancelAndJoin()
            camera.cancelAndJoin()
            hub.close()
        }
    }

    /**
     * An unpaired phone: no pair key here (RESUME refused), no code on screen (PAIR refused), a forged key for a paired
     * id (proof fails), and plaintext signalling on the control port (closed without an answer).
     */
    private suspend fun strangerIsRefused(port: Int, cameraId: LocalIdentity, monitorId: LocalIdentity) {
        val stranger = LocalIdentity(Secrets.newDeviceId(), "Stranger", DeviceRole.MONITOR)
        val attempts = listOf(
            ClientHandshake(stranger, HandshakeMode.RESUME, cameraId.deviceId, Secrets.randomBytes(Secrets.KEY_BYTES)) to RejectCode.UNKNOWN_DEVICE,
            ClientHandshake(stranger, HandshakeMode.PAIR, cameraId.deviceId, Secrets.randomBytes(Secrets.KEY_BYTES)) to RejectCode.PAIRING_CLOSED,
            ClientHandshake(monitorId, HandshakeMode.RESUME, cameraId.deviceId, Secrets.randomBytes(Secrets.KEY_BYTES)) to RejectCode.BAD_PROOF
        )
        for ((handshake, code) in attempts) {
            try {
                ControlSockets.connect("127.0.0.1", port, handshake).close()
                fail("an unpaired handshake was accepted")
            } catch (e: HandshakeException) {
                assertEquals(code, e.code)
            }
        }
        val plaintextAnswer = withContext(Dispatchers.IO) {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().write((SignallingCodec.encode(SignallingMessage.Hello(1, MonitorRole.MONITOR)) + "\n").toByteArray())
                socket.getOutputStream().flush()
                runCatching { socket.getInputStream().readBytes() }.getOrDefault(ByteArray(0))
            }
        }
        assertFalse("no offer in the clear", plaintextAnswer.toString(Charsets.ISO_8859_1).contains("offer"))
    }
}
