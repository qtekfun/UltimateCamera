package com.qtekfun.ultimatecamera.feature.multicam.monitor

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatecamera.core.net.clock.ActiveCommonClock
import com.qtekfun.ultimatecamera.core.net.clock.ClockClient
import com.qtekfun.ultimatecamera.core.net.clock.ClockEstimator
import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.clock.SyncedClock
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import com.qtekfun.ultimatecamera.core.net.role.ActionResult
import com.qtekfun.ultimatecamera.core.net.role.CameraActions
import com.qtekfun.ultimatecamera.core.net.role.CameraDriver
import com.qtekfun.ultimatecamera.core.net.role.CameraNode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The cameras a monitor or master controls, with links that cannot connect (nothing listens on port 1). */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCamerasViewModelTest {
    private val store = ViewModelStore()
    private val local = LocalIdentity("a".repeat(32), "Master", DeviceRole.MASTER)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        store.clear() // Stops the links.
        ActiveCommonClock.current = null
        Dispatchers.resetMain()
    }

    private val nanos = MonotonicClock { System.nanoTime() }

    /** JVM clocks and a free UDP port instead of SystemClock and 47803. */
    private fun testClockSource() = SessionClockSource(nanos, wallNanos = { WALL_NANOS }, port = 0, log = {})

    private fun target(n: Int) = RemoteCameraTarget("cam$n", "Cam $n", ByteArray(32)) { listOf(Endpoint("127.0.0.1", 1)) }

    private fun viewModel(count: Int, syncedStart: SyncedStart? = null, links: Boolean = true): RemoteCamerasViewModel {
        val factory = viewModelFactory {
            initializer {
                RemoteCamerasViewModel(
                    (1..count).map(::target),
                    local,
                    syncedStart,
                    commonNow = { null },
                    vibrate = {},
                    elapsedMs = { 1_000L },
                    clockSource = testClockSource(),
                    startLink = if (links) ({ camera, scope -> camera.start(scope) }) else ({ _, _ -> })
                )
            }
        }
        return ViewModelProvider.create(store, factory)[RemoteCamerasViewModel::class]
    }

    private fun RemoteCamerasViewModel.awaitUi(condition: (List<RemoteCameraUi>) -> Boolean) = runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(5_000) { ui.first(condition) }
            ?: error("ui=${ui.value.map { "${it.link} ${it.rec}" }} snap=${cameras.map { it.snapshot.value }}")
    }

    @Test
    fun atMostFourCamerasAreControlled() {
        val vm = viewModel(6)
        assertEquals(RemoteCamerasViewModel.MAX_CAMERAS, vm.cameras.size)
        assertEquals(listOf("cam1", "cam2", "cam3", "cam4"), vm.ui.value.map { it.id })
        assertTrue(vm.ui.value.map { "${it.link} ${it.rec}" }.toString(), vm.ui.value.all { it.link != LinkUi.LIVE && it.rec == RecUi.IDLE })
    }

    /**
     * Deterministic (D92): it failed only under full-suite load. The screen's own work (clock server, tile timer) runs on
     * a [StandardTestDispatcher] whose virtual time never advances and no link dials out, so nothing real runs while it
     * checks, and coroutines left by earlier tests that resume on Main only queue there.
     */
    @Test
    fun thumbnailsStartOnForOneOrTwoCamerasOnly() {
        Dispatchers.setMain(StandardTestDispatcher())
        val two = viewModel(2, links = false)
        assertEquals(2, two.cameras.size)
        assertTrue(two.thumbnails.value)
        store.clear()
        val many = viewModel(3, links = false)
        assertEquals(3, many.cameras.size)
        assertFalse(many.thumbnails.value)
        many.setThumbnails(true)
        assertTrue(many.thumbnails.value)
    }

    @Test
    fun unknownCamerasAreIgnored() {
        val vm = viewModel(1)
        assertNull(vm.camera("other"))
        assertNull(vm.rtt("cam1")) // Never connected: no round trip yet.
        vm.toggleRecording("other")
        vm.send("other", CommandBody.StopRecording(null))
        vm.clearError("other")
        assertEquals(RecUi.IDLE, vm.ui.value.single().rec)
    }

    @Test
    fun recIsOptimisticAndStopGoesBack() {
        val vm = viewModel(1)
        vm.toggleRecording("cam1")
        assertEquals(RecUi.STARTING, vm.awaitUi { it.single().rec != RecUi.IDLE }.single().rec)
        // While a start is pending the button shows recording: the next press stops.
        vm.toggleRecording("cam1")
        assertEquals(RecUi.IDLE, vm.awaitUi { it.single().rec != RecUi.STARTING }.single().rec)
    }

    @Test
    fun recordAllStartsEveryIdleCameraWithOneIdAtThePlannedTime() {
        val vm = viewModel(2, syncedStart = { 123L })
        vm.recordAll()
        val ui = vm.awaitUi { cams -> cams.all { it.rec == RecUi.STARTING } }
        assertEquals(2, ui.size)
        // Nothing idle any more: a second REC all sends nothing new.
        vm.recordAll()
        vm.stopAll()
        assertTrue(vm.awaitUi { cams -> cams.all { it.rec == RecUi.IDLE } }.isNotEmpty())
    }

    @Test
    fun aThrowingSyncedStartStillStartsAtOnce() {
        val plan = GroupStartPlanner.plan({ error("no clock") }, listOf(1L), "rec")
        assertEquals(GroupStart("rec", null), plan)
        val vm = viewModel(1, syncedStart = { error("no clock") })
        vm.recordAll()
        assertEquals(RecUi.STARTING, vm.awaitUi { it.single().rec != RecUi.IDLE }.single().rec)
    }

    /**
     * A camera on loopback (its real node and driver, as CameraRoleBinding runs them) syncs its clock to this phone: the
     * monitor/master is the session's clock source (REQ-NET-005). Before, nothing ran a clock server, so the camera's
     * clock client dialled 47803 forever and synchronised REC started on arrival.
     */
    @Test
    fun theCameraSyncsItsClockToThisPhoneAndRecIsPlannedOnIt() = runBlocking {
        withTimeout(20_000) {
            val confined = Dispatchers.Default.limitedParallelism(1)
            val cameraScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pairKey = ByteArray(32) { 7 }
            val cameraId = LocalIdentity(Secrets.newDeviceId(), "Camera", DeviceRole.CAMERA)
            val master = LocalIdentity(Secrets.newDeviceId(), "Master", DeviceRole.MASTER)
            val keyring = object : Keyring {
                override fun pairKey(deviceId: String): ByteArray? = pairKey.takeIf { deviceId == master.deviceId }

                override fun pairingToken(): ByteArray? = null

                override fun accepts(role: DeviceRole): Boolean = role.isController
            }
            val source = testClockSource()
            val estimator = ClockEstimator()
            val synced = SyncedClock(estimator)
            val clockKeys = ConcurrentHashMap<String, ByteArray>()
            var clockJob: Job? = null
            val node = CameraNode(
                "boot",
                nanos,
                synced,
                NoActions,
                onClockSource = source@{ peerId ->
                    clockJob?.cancel()
                    val key = peerId?.let { clockKeys[it] } ?: return@source
                    clockJob = cameraScope.launch {
                        while (source.boundPort == 0) delay(5)
                        ClockClient("127.0.0.1", source.boundPort, key, nanos, estimator).run()
                    }
                }
            )
            val bound = CompletableDeferred<Int>()
            CameraDriver(
                node = node,
                clock = nanos,
                local = cameraId,
                keyring = keyring,
                port = 0,
                onBound = { bound.complete(it) },
                onRemote = { session, _ -> clockKeys[session.peerId] = session.clockKey }
            ).launchIn(cameraScope, confined)
            val port = bound.await()
            val target = RemoteCameraTarget(cameraId.deviceId, "Camera", pairKey) { listOf(Endpoint("127.0.0.1", port)) }
            val factory = viewModelFactory {
                initializer {
                    RemoteCamerasViewModel(listOf(target), master, null, { null }, {}, elapsedMs = { System.nanoTime() / 1_000_000 }, clockSource = source)
                }
            }
            ViewModelProvider.create(store, factory)[RemoteCamerasViewModel::class]
            while (!synced.isSynced) delay(20)
            val now = nanos.nanos()
            val error = abs(synced.toCommon(now)!! - source.clock.toCommon(now))
            assertTrue("camera and monitor agree on the common time (error $error ns)", error < 20_000_000L)
            assertSame(source.clock, ActiveCommonClock.current)
            store.clear() // Leaving the monitor: the clock server stops (within its 0.5 s socket poll).
            while (ActiveCommonClock.current != null) delay(20)
            cameraScope.cancel()
        }
    }

    private object NoActions : CameraActions {
        override fun startRecording(recordingId: String) = ActionResult.Ok

        override fun stopRecording(recordingId: String) = ActionResult.Ok

        override fun applySetting(command: CommandBody) = ActionResult.Ok
    }

    private companion object {
        const val WALL_NANOS = 1_792_195_200_000_000_000L
    }
}
