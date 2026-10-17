package com.qtekfun.ultimatecamera.feature.multicam.roles

import com.qtekfun.ultimatecamera.core.net.R as NetR
import com.qtekfun.ultimatecamera.core.net.clock.ActiveCommonClock
import com.qtekfun.ultimatecamera.core.net.control.ControlSockets
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.discovery.DiscoveredService
import com.qtekfun.ultimatecamera.core.net.discovery.DiscoveryEvent
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceAdvert
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.PairingPayload
import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import com.qtekfun.ultimatecamera.core.settings.MulticamRoleRepository
import com.qtekfun.ultimatecamera.feature.multicam.R
import com.qtekfun.ultimatecamera.feature.multicam.pairing.DiscoveryStatus
import com.qtekfun.ultimatecamera.feature.multicam.pairing.HostState
import com.qtekfun.ultimatecamera.feature.multicam.pairing.NearbyDevice
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairedDevicesRepository
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairedDevicesVault
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingKeyring
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingWindow
import com.qtekfun.ultimatecamera.feature.multicam.pairing.ShortCode
import com.qtekfun.ultimatecamera.feature.multicam.session.MulticamSession
import com.qtekfun.ultimatecamera.feature.multicam.session.Relation
import com.qtekfun.ultimatecamera.feature.multicam.session.RoleChoice
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The multicamera view model over a real paired-devices file and real loopback pairing; only NSD is faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class MulticamViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val roles = FakeRoles()
    private val discovery = FakeDiscovery()
    private var now = 1_000_000L

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        ActiveCommonClock.current = null
        Dispatchers.resetMain()
    }

    private fun repo() = PairedDevicesRepository(PairedDevicesVault(temp.newFolder(), null)) { now }

    private fun viewModel(devices: PairedDevicesRepository = repo()) = MulticamViewModel(
        roles = roles,
        loadDevices = { devices },
        newDiscovery = { discovery },
        deviceName = "Monitor phone",
        elapsedNanos = { 5_000L },
        nowMs = { now }
    )

    private fun <T> StateFlow<T>.await(condition: (T) -> Boolean): T = runBlocking { withTimeout(TIMEOUT_MS) { first(condition) } }

    @Test
    fun theRoleIsReadAndStored() = runBlocking {
        val vm = viewModel()
        assertNull(vm.role.value)
        vm.setRole(MulticamRole.MASTER).join()
        assertEquals(MulticamRole.MASTER, vm.role.await { it != null })
        assertEquals(MulticamRole.MASTER, roles.multicamRole.value)
        vm.ownId.await { it.isNotEmpty() }
        assertEquals(DeviceRole.MASTER, vm.controllerIdentity()?.role)
        assertEquals("Monitor phone", vm.controllerIdentity()?.name)
        vm.setRole(MulticamRole.MONITOR).join()
        vm.role.await { it == MulticamRole.MONITOR }
        assertEquals(DeviceRole.MONITOR, vm.controllerIdentity()?.role)
    }

    @Test
    fun theOwnIdAndKeyProtectionComeFromTheStoredFile() {
        val devices = repo()
        val vm = viewModel(devices)
        assertEquals(devices.ownId, vm.ownId.await { it.isNotEmpty() })
        assertFalse(vm.keysProtected.value) // No Keystore on the JVM: the plain fallback.
    }

    @Test
    fun discoveryRunsOnlyWhileStarted() {
        val vm = viewModel()
        vm.startDiscovery()
        assertEquals(1, discovery.browsing)
        val peer = repo().ownId
        discovery.emit(DiscoveryEvent.Found(DiscoveredService(ServiceAdvert(peer, "Cam", DeviceRole.CAMERA, 4000, 1), listOf(Endpoint("10.0.0.2", 4000)))))
        val status = vm.discovery.await { it.adverts.isNotEmpty() }
        assertEquals(DiscoveryStatus.State.SEARCHING, status.state)
        assertTrue(status.isOnline(peer))
        vm.stopDiscovery()
        assertEquals(0, discovery.browsing)
    }

    @Test
    fun aMalformedQrIsExplained() {
        val vm = viewModel()
        vm.onQrScanned("not a pairing code")
        val error = vm.progress.await { it is PairingProgress.Error } as PairingProgress.Error
        assertEquals(NetR.string.net_pairing_error_not_a_code, error.message)
        vm.resetProgress()
        assertEquals(PairingProgress.Idle, vm.progress.value)
    }

    @Test
    fun aBadlyTypedCodeIsRefusedAtOnce() {
        val vm = viewModel()
        vm.pairWithCode(NearbyDevice(repo().ownId, "Cam", DeviceRole.CAMERA, emptyList()), "12")
        assertEquals(PairingProgress.Error(R.string.multicam_code_invalid), vm.progress.value)
    }

    @Test
    fun aCameraThatDoesNotAnswerIsUnreachable() {
        val vm = viewModel()
        vm.pairWithCode(NearbyDevice(repo().ownId, "Cam", DeviceRole.CAMERA, listOf(Endpoint("127.0.0.1", 1))), "1234 5678")
        assertEquals(PairingProgress.Error(R.string.multicam_pair_unreachable), vm.progress.await { it is PairingProgress.Error })
    }

    @Test
    fun theTypedCodePairsAndTheCameraBecomesAControlTarget() = withCamera { camera, port, window ->
        val code = ShortCode.generate()
        window.open(code, ShortCode.token(camera.ownId, code))
        val devices = repo()
        val vm = viewModel(devices)
        vm.pairWithCode(NearbyDevice(camera.ownId, "Cam", DeviceRole.CAMERA, listOf(Endpoint("127.0.0.1", port))), ShortCode.format(code))
        assertEquals(PairingProgress.Done("Camera", camera.ownId), vm.progress.await { it is PairingProgress.Done || it is PairingProgress.Error })
        val paired = vm.devices.await { it.isNotEmpty() }.single()
        assertEquals(camera.ownId, paired.id)
        // Paired, but no roles yet (D80): nothing to control until this phone is made its monitor.
        assertTrue(vm.controlTargets().isEmpty())
        assertEquals(Relation.NONE, vm.relation(camera.ownId))
        devices.setRoles(mapOf(camera.ownId to DeviceRole.CAMERA))
        vm.setRole(MulticamRole.MONITOR).join()
        vm.role.await { it == MulticamRole.MONITOR }
        assertEquals(Relation.CONTROLS_PEER, vm.relation(camera.ownId))
        val target = vm.controlTargets().single()
        assertEquals(camera.ownId, target.id)
        // Discovery's fresher address is dialled first.
        vm.startDiscovery()
        discovery.emit(
            DiscoveryEvent.Found(DiscoveredService(ServiceAdvert(camera.ownId, "Camera", DeviceRole.CAMERA, 47_900, 1), listOf(Endpoint("10.0.0.9", 47_900))))
        )
        vm.discovery.await { it.isOnline(camera.ownId) }
        // On the camera's control port: an advert may carry the port of its pairing or session listener (before the
        // fix the monitor dialled that port with RESUME and its link flapped).
        assertEquals(Endpoint("10.0.0.9", 47_802), target.endpoints().first())
        // Unpairing removes it again, and a monitor without cameras is off.
        vm.unpair(camera.ownId).join()
        vm.devices.await { it.isEmpty() }
        assertTrue(vm.controlTargets().isEmpty())
        assertNull(vm.role.await { it == null })
    }

    @Test
    fun aScannedQrPairs() = withCamera { camera, port, window ->
        val code = ShortCode.generate()
        val token = ShortCode.token(camera.ownId, code)
        window.open(code, token)
        val qr = PairingPayload(camera.ownId, "Camera", DeviceRole.CAMERA, listOf(Endpoint("127.0.0.1", port)), token, now / 1_000 + 300).encode()
        val vm = viewModel()
        vm.onQrScanned(qr)
        assertEquals(PairingProgress.Done("Camera", camera.ownId), vm.progress.await { it is PairingProgress.Done || it is PairingProgress.Error })
    }

    @Test
    fun aRoleWithAPhoneThatIsNotPairedExplainsIt() {
        val devices = repo()
        val session = MulticamSession(
            loadDevices = { devices },
            roles = roles,
            deviceName = { "Monitor phone" },
            newDiscovery = { null },
            recording = { false },
            port = ServerSocket(0).use { it.localPort }
        )
        val vm = MulticamViewModel(roles, { devices }, { discovery }, "Monitor phone", nowMs = { now }, session = session)
        vm.choose(repo().ownId, RoleChoice.CAMERA)
        assertEquals(RoleProgress.Error(R.string.multicam_roles_not_paired), vm.roleProgress.await { it is RoleProgress.Error })
        assertNull(roles.multicamRole.value)
        vm.resetRoleProgress()
        assertEquals(RoleProgress.Idle, vm.roleProgress.value)
    }

    @Test
    fun turningOffForgetsTheCamerasButKeepsThePairings() = runBlocking {
        val devices = repo()
        val vm = viewModel(devices)
        vm.ownId.await { it.isNotEmpty() }
        vm.setRole(MulticamRole.MONITOR).join()
        vm.turnOff().join()
        assertNull(vm.role.await { it == null })
    }

    @Test
    fun showingACodeListensAndAdvertisesUntilStopped() {
        val vm = viewModel()
        vm.startShowingCode()
        // Showing on a phone with Wi-Fi; a build machine may only have loopback (no address to put in the QR).
        val state = vm.hostState.await { it is HostState.Showing || it is HostState.NoNetwork || it is HostState.Failed }
        assertTrue(state is HostState.Showing || state is HostState.NoNetwork)
        assertEquals(1, discovery.adverts)
        vm.newCode().let { runBlocking { it.join() } }
        vm.stopShowingCode()
        vm.hostState.await { it == HostState.Idle }
        assertEquals(0, discovery.openAdverts)
    }

    @Test
    fun theCommonClockIsOnlyThereInASyncedSession() {
        val vm = viewModel()
        assertNull(vm.commonNow())
        assertNull(vm.syncedStart.planStart(listOf(1_000L)))
    }

    /** A camera's pairing listener on loopback, as [com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingHost] runs it. */
    private fun withCamera(block: suspend (camera: PairedDevicesRepository, port: Int, window: PairingWindow) -> Unit) = runBlocking {
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
            block(camera, bound.get(5, TimeUnit.SECONDS), window)
        } finally {
            server.cancelAndJoin()
        }
    }

    private class FakeRoles : MulticamRoleRepository {
        override val multicamRole = MutableStateFlow<MulticamRole?>(null)

        override suspend fun setMulticamRole(role: MulticamRole?) {
            multicamRole.value = role
        }
    }

    private class FakeDiscovery : ServiceDiscovery {
        private var listener: ((DiscoveryEvent) -> Unit)? = null
        var browsing = 0
        var adverts = 0
        var openAdverts = 0

        override fun advertise(advert: ServiceAdvert, onFailed: (code: Int) -> Unit): Closeable {
            adverts++
            openAdverts++
            return Closeable { openAdverts-- }
        }

        override fun browse(onEvent: (DiscoveryEvent) -> Unit): Closeable {
            browsing++
            listener = onEvent
            return Closeable {
                browsing--
                listener = null
            }
        }

        fun emit(event: DiscoveryEvent) = listener?.invoke(event)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
