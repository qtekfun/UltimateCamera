package com.qtekfun.ultimatecamera.feature.multicam.session

import com.qtekfun.ultimatecamera.core.net.control.ControlSockets
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import com.qtekfun.ultimatecamera.core.settings.MulticamRoleRepository
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairOutcome
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairedDevicesRepository
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairedDevicesVault
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingClient
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingKeyring
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingWindow
import com.qtekfun.ultimatecamera.feature.multicam.pairing.ShortCode
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Two phones paired over loopback choose their roles on the session socket (real handshake, real TCP). */
class MulticamSessionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private class FakeRoles : MulticamRoleRepository {
        override val multicamRole = MutableStateFlow<MulticamRole?>(null)

        override suspend fun setMulticamRole(role: MulticamRole?) {
            multicamRole.value = role
        }
    }

    private class Phone(val repo: PairedDevicesRepository, val roles: FakeRoles, val session: MulticamSession, var recording: Boolean = false)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun phone(name: String, port: Int, dialPort: Int = port): Phone {
        val repo = PairedDevicesRepository(PairedDevicesVault(temp.newFolder(), null)) { 42L }
        val roles = FakeRoles()
        lateinit var p: Phone
        val session = MulticamSession(
            loadDevices = { repo },
            roles = roles,
            deviceName = { name },
            newDiscovery = { null },
            recording = { p.recording },
            port = port,
            dialPort = dialPort
        )
        p = Phone(repo, roles, session)
        return p
    }

    /** Pairs [a] (scanning) with [b] (showing the code), as the multicamera screens do. */
    private fun pair(a: Phone, b: Phone) = runBlocking {
        val window = PairingWindow()
        val bound = CompletableFuture<Int>()
        val local = LocalIdentity(b.repo.ownId, "B", SessionRoles.NEUTRAL)
        val server = launch(Dispatchers.IO) {
            ControlSockets.serve(
                port = 0,
                handshakeFactory = { ServerHandshake(local, PairingKeyring(b.repo, window, DeviceRole.entries.toSet())) },
                onSession = { c -> c.use { b.repo.addFromSession(it.session, listOf(Endpoint("127.0.0.1", 47_802)), SessionRoles.NEUTRAL) } },
                onBound = { bound.complete(it) }
            )
        }
        val code = ShortCode.generate()
        window.open(code, ShortCode.token(b.repo.ownId, code))
        val outcome = PairingClient(a.repo).pair(
            LocalIdentity(a.repo.ownId, "A", SessionRoles.NEUTRAL),
            b.repo.ownId,
            listOf(Endpoint("127.0.0.1", bound.get(5, TimeUnit.SECONDS))),
            ShortCode.token(b.repo.ownId, code)
        )
        assertTrue(outcome is PairOutcome.Paired)
        withTimeout(5_000) { while (!b.repo.isPaired(a.repo.ownId)) kotlinx.coroutines.delay(10) }
        server.cancelAndJoin()
    }

    private fun <T> MutableStateFlow<T>.await(condition: (T) -> Boolean): T = runBlocking { withTimeout(5_000) { first(condition) } }

    @Test
    fun choosingCameraOnOnePhoneMakesTheOtherItsMonitor() = runBlocking {
        val port = freePort()
        val a = phone("A", port)
        val b = phone("B", port)
        pair(a, b)
        b.session.start(scope)
        withTimeout(5_000) { b.session.listening.first { it } }
        val event = async { withTimeout(5_000) { b.session.assignments.first() } }

        val outcome = a.session.choose(b.repo.ownId, RoleChoice.CAMERA, hosts = listOf("127.0.0.1"))

        assertEquals(ChooseOutcome.Done(MulticamRole.CAMERA), outcome)
        assertEquals(MulticamRole.CAMERA, a.roles.multicamRole.value)
        assertEquals(MulticamRole.MONITOR, b.roles.multicamRole.value)
        // B controls A now: A is its camera (a control target), B is A's monitor.
        assertEquals(DeviceRole.CAMERA, b.repo.storedRoles()[a.repo.ownId])
        assertEquals(DeviceRole.MONITOR, a.repo.storedRoles()[b.repo.ownId])
        assertEquals(RoleAssignment(MulticamRole.MONITOR, a.repo.ownId, fromPeer = true), event.await())
        assertTrue(b.session.openControl.value)
        b.session.consumeOpenControl()
        assertFalse(b.session.openControl.value)
    }

    @Test
    fun rolesSwapWithoutPairingAgain() = runBlocking {
        val portA = freePort()
        val portB = freePort()
        val a = phone("A", portA, dialPort = portB)
        val b = phone("B", portB, dialPort = portA)
        pair(a, b)
        a.session.start(scope)
        b.session.start(scope)
        withTimeout(5_000) { a.session.listening.first { it } }
        withTimeout(5_000) { b.session.listening.first { it } }
        assertEquals(ChooseOutcome.Done(MulticamRole.CAMERA), a.session.choose(b.repo.ownId, RoleChoice.CAMERA, listOf("127.0.0.1")))
        // Swap from the other phone: B becomes the camera, A its monitor. B reaches A at the address A was seen from.
        assertEquals(ChooseOutcome.Done(MulticamRole.CAMERA), b.session.choose(a.repo.ownId, RoleChoice.CAMERA))
        assertEquals(MulticamRole.MONITOR, a.roles.multicamRole.value)
        assertEquals(DeviceRole.CAMERA, a.repo.storedRoles()[b.repo.ownId])
        assertEquals(DeviceRole.MONITOR, b.repo.storedRoles()[a.repo.ownId])
        // And end it: both off.
        assertEquals(ChooseOutcome.Done(null), a.session.choose(b.repo.ownId, RoleChoice.END))
        assertEquals(null, a.roles.multicamRole.value)
        assertEquals(null, b.roles.multicamRole.value)
    }

    @Test
    fun aRecordingPhoneRefusesAndNothingChanges() = runBlocking {
        val port = freePort()
        val a = phone("A", port)
        val b = phone("B", port)
        pair(a, b)
        b.roles.multicamRole.value = MulticamRole.CAMERA
        b.recording = true
        b.session.start(scope)
        withTimeout(5_000) { b.session.listening.first { it } }
        assertEquals(ChooseOutcome.PeerBusy, a.session.choose(b.repo.ownId, RoleChoice.CAMERA, listOf("127.0.0.1")))
        assertEquals(null, a.roles.multicamRole.value)
        assertEquals(MulticamRole.CAMERA, b.roles.multicamRole.value)
    }

    @Test
    fun anUnreachablePhoneChangesNothingButEndingStillEndsHere() = runBlocking {
        val a = phone("A", freePort())
        val b = phone("B", freePort())
        pair(a, b)
        assertEquals(ChooseOutcome.Unreachable, a.session.choose(b.repo.ownId, RoleChoice.MONITOR, listOf("127.0.0.1")))
        assertEquals(null, a.roles.multicamRole.value)
        a.roles.multicamRole.value = MulticamRole.MONITOR
        a.repo.setRoles(mapOf(b.repo.ownId to DeviceRole.CAMERA))
        assertEquals(ChooseOutcome.Done(null), a.session.choose(b.repo.ownId, RoleChoice.END, listOf("127.0.0.1")))
        assertEquals(SessionRoles.NEUTRAL, a.repo.storedRoles()[b.repo.ownId])
    }

    @Test
    fun aStrangerIsNotPaired() = runBlocking {
        val port = freePort()
        val a = phone("A", port)
        val b = phone("B", port)
        val stranger = phone("C", port)
        pair(a, b)
        pair(stranger, a) // so the stranger has a key, but for A, not for B
        b.session.start(scope)
        withTimeout(5_000) { b.session.listening.first { it } }
        // Forged: the stranger uses its key for A to reach B as if it were paired with it.
        val outcome = SessionSocket.send(
            LocalIdentity(stranger.repo.ownId, "C", SessionRoles.NEUTRAL),
            b.repo.ownId,
            stranger.repo.pairKey(a.repo.ownId)!!,
            listOf("127.0.0.1"),
            com.qtekfun.ultimatecamera.core.net.control.ControlMessage.Roles("x", DeviceRole.CAMERA, DeviceRole.MONITOR),
            port
        )
        assertEquals(SessionSocket.SendResult.NotPaired, outcome)
        assertEquals(null, b.roles.multicamRole.value)
    }
}
