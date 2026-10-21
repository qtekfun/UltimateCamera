package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedCamera
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedMode
import com.qtekfun.ultimatecamera.core.capabilities.CameraCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.CodecType
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DeviceIdentity
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange
import com.qtekfun.ultimatecamera.core.capabilities.LutMode
import com.qtekfun.ultimatecamera.core.capabilities.ProbeOutcome
import com.qtekfun.ultimatecamera.core.capabilities.ProbeResult
import com.qtekfun.ultimatecamera.core.capabilities.ProbeRun
import com.qtekfun.ultimatecamera.core.capabilities.StopReason
import com.qtekfun.ultimatecamera.core.capabilities.VendorKeyFamily
import com.qtekfun.ultimatecamera.core.capabilities.VideoConfig
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.settings.AppSettings
import com.qtekfun.ultimatecamera.core.settings.ControlOption
import com.qtekfun.ultimatecamera.core.settings.FeatureGroup
import com.qtekfun.ultimatecamera.core.settings.HdrPrefs
import com.qtekfun.ultimatecamera.core.settings.HdrRepository
import com.qtekfun.ultimatecamera.core.settings.LookSlot
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConfigViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = FakeSettings()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A session the test drives: it reports [results] one by one, waits for [finish], then returns [run]. */
    private class FakeSession(
        private val results: List<ProbeResult>,
        private val measured: AutoConfigCameras = AutoConfigCameras("0"),
        private val run: () -> ProbeRun
    ) : AutoConfigSession {
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        var experimental: Boolean? = null

        override suspend fun run(
            experimental: Boolean,
            cameras: (AutoConfigCameras) -> Unit,
            listener: com.qtekfun.ultimatecamera.core.capabilities.ProbeListener
        ): AutoConfigRun {
            calls++
            this.experimental = experimental
            cameras(measured)
            results.forEachIndexed { i, r -> listener.onResult(i + 1, results.size, r) }
            finish.await()
            return AutoConfigRun(run(), measured)
        }
    }

    private fun TestScope.viewModel(session: AutoConfigSession) = AutoConfigViewModel(settings, session) { testScheduler.currentTime }

    @Test
    fun aRunShowsEveryResultThenTheBestMode() = runTest(dispatcher) {
        val results = listOf(result(3840, 2160, 60, ProbeOutcome.SESSION_REJECTED), result(3840, 2160, 30, ProbeOutcome.ACCEPTED_AND_HELD))
        val session = FakeSession(results) { ProbeRun(capabilities(results), stopReason = null, sessionsRun = 2, elapsedMs = 42_000) }
        val vm = viewModel(session)
        vm.start()
        assertEquals(AutoConfigPhase.RUNNING, vm.state.value.phase)
        runCurrent()
        assertEquals(2, vm.state.value.done)
        assertEquals(2, vm.state.value.total)
        assertEquals(2, vm.state.value.rows.size)
        assertEquals(ProbeOutcome.SESSION_REJECTED, vm.state.value.rows.first().outcome)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.elapsedMs >= 750) // The timer ticks while the probes run.
        vm.start() // Ignored while running.
        session.finish.complete(Unit)
        advanceUntilIdle()
        val done = vm.state.value
        assertEquals(1, session.calls)
        assertEquals(false, session.experimental)
        assertEquals(AutoConfigPhase.DONE, done.phase)
        assertEquals(42_000, done.elapsedMs)
        assertNull(done.stopReason)
        assertEquals(RecordingProfile(3840, 2160, 30, VideoCodec.HEVC, tenBit = true), done.best)
    }

    @Test
    fun experimentalAddsTheVendorVariantsAndAThermalStopIsShown() = runTest(dispatcher) {
        settings.hdrPrefs.value = HdrPrefs(experimental = true)
        val session = FakeSession(emptyList()) { ProbeRun(capabilities(emptyList()), StopReason.THERMAL, sessionsRun = 0, elapsedMs = 1) }
        session.finish.complete(Unit)
        val vm = viewModel(session)
        vm.start()
        advanceUntilIdle()
        assertEquals(true, session.experimental)
        assertEquals(AutoConfigPhase.DONE, vm.state.value.phase)
        assertEquals(StopReason.THERMAL, vm.state.value.stopReason)
        assertNull(vm.state.value.best) // Nothing held.
    }

    @Test
    fun aFinishedRunListsTheManufacturerKeysOfTheMeasuredCamera() = runTest(dispatcher) {
        val keyed = capabilities(emptyList()).let { caps ->
            val camera = caps.cameras.single()
            caps.copy(
                cameras = listOf(
                    camera.copy(
                        advertised = camera.advertised.copy(
                            vendorRequestKeys = listOf("com.vendor.scene", "com.vendor.fps"),
                            vendorSessionKeys = listOf("com.vendor.fps")
                        )
                    )
                )
            )
        }
        val session = FakeSession(emptyList()) { ProbeRun(keyed, null, 0, 0) }
        session.finish.complete(Unit)
        val vm = viewModel(session)
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf(VendorKeyFamily("com.vendor", keys = 2, sessionKeys = 1)), vm.state.value.vendorFamilies)
        // D105: a session list that is a real subset is shown; one that repeats every key (D102) is not.
        assertEquals("com.vendor 2 (1)", vendorKeysText(vm.state.value.vendorFamilies))
        assertEquals("org.codeaurora 54", vendorKeysText(listOf(VendorKeyFamily("org.codeaurora", keys = 54, sessionKeys = 54))))
    }

    @Test
    fun aPlatformFailureEndsTheRunWithItsMessage() = runTest(dispatcher) {
        val vm = viewModel { _, _, _ -> error("no camera") }
        vm.start()
        advanceUntilIdle()
        assertEquals(AutoConfigPhase.FAILED, vm.state.value.phase)
        assertEquals("IllegalStateException: no camera", vm.state.value.error)
    }

    @Test
    fun cancellingGoesBackToIdleAndAllowsANewRun() = runTest(dispatcher) {
        val session = FakeSession(emptyList()) { ProbeRun(capabilities(emptyList()), null, 0, 0) }
        val vm = viewModel(session)
        vm.start()
        runCurrent()
        vm.cancel()
        runCurrent()
        assertEquals(AutoConfigPhase.IDLE, vm.state.value.phase)
        vm.start()
        runCurrent()
        assertEquals(2, session.calls)
        vm.cancel()
    }

    @Test
    fun markOfferedIsStored() = runTest(dispatcher) {
        val vm = viewModel { _, _, _ -> error("unused") }
        assertFalse(settings.autoConfigOffered)
        vm.markOffered()
        advanceUntilIdle()
        assertTrue(settings.autoConfigOffered)
    }

    private fun result(w: Int, h: Int, fps: Int, outcome: ProbeOutcome, cameraId: String = "0") = ProbeResult(
        cameraId = cameraId,
        config = VideoConfig(w, h, fps, CodecType.HEVC, DynamicRange.HLG10, LutMode.HARDWARE),
        advertised = true,
        outcome = outcome
    )

    @Test
    fun theFrontCameraIsMeasuredTooItsRowsGoUnderItAndItHasItsOwnBestMode() = runTest(dispatcher) {
        // The plan runs the front camera's ladder before the main camera's variants (D107).
        val selfie = result(1920, 1080, 30, ProbeOutcome.ACCEPTED_AND_HELD, cameraId = "1")
        val results = listOf(
            result(3840, 2160, 30, ProbeOutcome.ACCEPTED_AND_HELD),
            selfie.copy(config = selfie.config.copy(dynamicRange = DynamicRange.STANDARD)),
            result(3840, 2160, 60, ProbeOutcome.SESSION_REJECTED)
        )
        val front = CameraCapabilities(
            AdvertisedCamera("1", "FRONT", videoModes = listOf(AdvertisedMode(1920, 1080, 30)), tenBitProfiles = listOf(DynamicRange.HLG10)),
            results.filter { it.cameraId == "1" }
        )
        val caps = capabilities(results.filter { it.cameraId == "0" }).let { it.copy(cameras = it.cameras + front) }
        val session = FakeSession(results, AutoConfigCameras("0", "1")) { ProbeRun(caps, null, 3, 1) }
        val vm = viewModel(session)
        vm.start()
        runCurrent()
        // Known before the run ends, so the list is grouped while it fills.
        assertEquals(AutoConfigCameras("0", "1"), vm.state.value.cameras)
        assertEquals(listOf("0", "1"), vm.state.value.rowsByCamera.map { it.first })
        assertEquals(2, vm.state.value.rowsByCamera.first().second.size)
        session.finish.complete(Unit)
        advanceUntilIdle()
        assertEquals(RecordingProfile(3840, 2160, 30, VideoCodec.HEVC, tenBit = true), vm.state.value.best)
        assertEquals(RecordingProfile(1920, 1080, 30, VideoCodec.HEVC, tenBit = false), vm.state.value.frontBest)
    }

    @Test
    fun anUpscaledModeMakesTheScreenExplainItsMark() {
        // D112: "↑3264x1836" alone would not say the 4K is scaled up from the sensor's real size.
        val upscaled = VideoConfig(3840, 2160, 30, CodecType.HEVC, DynamicRange.HLG10, LutMode.HARDWARE, sourceWidth = 3264, sourceHeight = 1836)
        val plain = upscaled.copy(sourceWidth = null, sourceHeight = null)
        fun row(c: VideoConfig) = ProbeRow(ProbeResult("1", c, advertised = true, outcome = ProbeOutcome.ACCEPTED_AND_HELD))
        assertFalse(AutoConfigUiState(rows = listOf(row(plain))).showsUpscaled)
        assertTrue(AutoConfigUiState(rows = listOf(row(plain), row(upscaled))).showsUpscaled)
        assertTrue(AutoConfigUiState(frontBest = RecordingProfile(3840, 2160, 30, sourceWidth = 3264, sourceHeight = 1836)).showsUpscaled)
    }

    @Test
    fun theScreenStaysOnOnlyWhileARunIsInProgress() {
        // D108: the screen timeout stopped the app and the run with it.
        assertTrue(keepsScreenOn(AutoConfigPhase.RUNNING))
        AutoConfigPhase.entries.filter { it != AutoConfigPhase.RUNNING }.forEach { assertFalse(keepsScreenOn(it)) }
    }

    private fun capabilities(results: List<ProbeResult>) = DeviceCapabilities(
        identity = DeviceIdentity("Google", "google", "Pixel 8", "shiba", 36),
        engineVersion = 1,
        cameras = listOf(
            CameraCapabilities(
                AdvertisedCamera("0", "BACK", videoModes = listOf(AdvertisedMode(3840, 2160, 60)), tenBitProfiles = listOf(DynamicRange.HLG10)),
                results
            )
        )
    )
}

/** In-memory settings for the camera feature's view models (the optional HDR section included). */
class FakeSettings :
    SettingsRepository,
    HdrRepository {
    override val settings = MutableStateFlow(AppSettings.DEFAULT)
    override val hdrPrefs = MutableStateFlow(HdrPrefs())
    var autoConfigOffered = false

    override suspend fun setAutoConfigOffered(offered: Boolean) {
        autoConfigOffered = offered
    }

    override suspend fun setFeatureGroupEnabled(group: FeatureGroup, enabled: Boolean) = Unit

    override suspend fun setMaxBrightness(enabled: Boolean) = Unit

    override suspend fun setControlOption(option: ControlOption, enabled: Boolean) = Unit

    override suspend fun setRecordingTreeUri(uri: String?) = Unit

    override suspend fun setLook(slot: LookSlot, token: String?) = Unit

    override suspend fun setRecordingLocationMode(mode: String?) = Unit

    override suspend fun setAudioInputKey(key: String?) = Unit

    override suspend fun setAudioGainDb(gainDb: Float) = Unit

    override suspend fun setSeparateWav(enabled: Boolean) = Unit

    override suspend fun resetToDefaults() = Unit

    override suspend fun setHdrPrefs(prefs: HdrPrefs) {
        hdrPrefs.value = prefs
    }
}
