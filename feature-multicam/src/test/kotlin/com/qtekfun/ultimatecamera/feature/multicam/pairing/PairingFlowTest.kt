package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.control.ControlSockets
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.RejectCode
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Camera and monitor pair over loopback sockets with the typed code, as on two phones (core-net handshake, real TCP). */
class PairingFlowTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun repo() = PairedDevicesRepository(PairedDevicesVault(temp.newFolder(), null)) { 42L }

    private fun withCamera(block: suspend (camera: PairedDevicesRepository, cameraId: String, port: Int, window: PairingWindow) -> Unit) = runBlocking {
        val camera = repo()
        val window = PairingWindow()
        val local = LocalIdentity(camera.ownId, "Camera", DeviceRole.CAMERA)
        val bound = CompletableFuture<Int>()
        val server = launch(Dispatchers.IO) {
            ControlSockets.serve(
                port = 0,
                handshakeFactory = { ServerHandshake(local, PairingKeyring(camera, window)) },
                onSession = { connection -> connection.use { camera.addFromSession(it.session) } },
                onBound = { bound.complete(it) }
            )
        }
        try {
            block(camera, camera.ownId, bound.get(5, TimeUnit.SECONDS), window)
        } finally {
            server.cancelAndJoin()
        }
    }

    @Test
    fun theTypedCodePairsBothPhonesWithTheSameKey() = withCamera { camera, cameraId, port, window ->
        val code = ShortCode.generate()
        window.open(code, ShortCode.token(cameraId, code))
        val monitor = repo()
        val outcome = PairingClient(monitor).pair(
            LocalIdentity(monitor.ownId, "Monitor", DeviceRole.MONITOR),
            cameraId,
            listOf(Endpoint("127.0.0.1", port)),
            ShortCode.token(cameraId, ShortCode.normalize(ShortCode.format(code))!!)
        )
        assertTrue(outcome is PairOutcome.Paired)
        val stored = monitor.devices.value.single()
        assertEquals(cameraId, stored.id)
        assertEquals(DeviceRole.CAMERA, stored.role)
        assertEquals(listOf(Endpoint("127.0.0.1", 47_802)), stored.endpoints) // the control port, not the pairing port
        waitFor { camera.isPaired(monitor.ownId) }
        assertArrayEquals(monitor.pairKey(cameraId), camera.pairKey(monitor.ownId))
        assertEquals(WindowState.Used, window.state.value)

        // One use: the same code again is refused.
        val again = PairingClient(
            repo()
        ).pair(LocalIdentity(repo().ownId, "Other", DeviceRole.MONITOR), cameraId, listOf(Endpoint("127.0.0.1", port)), ShortCode.token(cameraId, code))
        assertEquals(PairOutcome.Refused(RejectCode.PAIRING_CLOSED), again)
    }

    @Test
    fun aWrongCodeIsRefusedAndBurnsTheShownCode() = withCamera { camera, cameraId, port, window ->
        window.open("12345678", ShortCode.token(cameraId, "12345678"))
        val monitor = repo()
        val outcome = PairingClient(monitor).pair(
            LocalIdentity(monitor.ownId, "Monitor", DeviceRole.MONITOR),
            cameraId,
            listOf(Endpoint("127.0.0.1", port)),
            ShortCode.token(cameraId, "87654321")
        )
        assertEquals(PairOutcome.Refused(RejectCode.BAD_PROOF), outcome)
        assertTrue(monitor.devices.value.isEmpty())
        assertTrue(camera.devices.value.isEmpty())
        assertEquals(WindowState.Used, window.state.value)
    }

    @Test
    fun aCameraCannotPairWithACamera() = withCamera { _, cameraId, port, window ->
        window.open("12345678", ShortCode.token(cameraId, "12345678"))
        val other = repo()
        val outcome = PairingClient(other).pair(
            LocalIdentity(other.ownId, "Cam 2", DeviceRole.CAMERA),
            cameraId,
            listOf(Endpoint("127.0.0.1", port)),
            ShortCode.token(cameraId, "12345678")
        )
        assertEquals(PairOutcome.Refused(RejectCode.ROLE_NOT_ALLOWED), outcome)
    }

    @Test
    fun nothingListeningIsUnreachable() = runBlocking {
        val monitor = repo()
        val outcome = PairingClient(monitor).pair(
            LocalIdentity(monitor.ownId, "Monitor", DeviceRole.MONITOR),
            repo().ownId,
            listOf(Endpoint("127.0.0.1", 1)),
            ByteArray(32)
        )
        assertEquals(PairOutcome.Unreachable, outcome)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
}
