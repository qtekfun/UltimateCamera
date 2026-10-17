package com.qtekfun.ultimatecamera.feature.multicam.monitor

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        Dispatchers.resetMain()
    }

    private fun target(n: Int) = RemoteCameraTarget("cam$n", "Cam $n", ByteArray(32)) { listOf(Endpoint("127.0.0.1", 1)) }

    private fun viewModel(count: Int, syncedStart: SyncedStart? = null): RemoteCamerasViewModel {
        val factory = viewModelFactory {
            initializer {
                RemoteCamerasViewModel((1..count).map(::target), local, syncedStart, commonNow = { null }, vibrate = {}, elapsedMs = { 1_000L })
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

    @Test
    fun thumbnailsStartOnForOneOrTwoCamerasOnly() {
        assertTrue(viewModel(2).thumbnails.value)
        store.clear()
        val many = viewModel(3)
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
}
