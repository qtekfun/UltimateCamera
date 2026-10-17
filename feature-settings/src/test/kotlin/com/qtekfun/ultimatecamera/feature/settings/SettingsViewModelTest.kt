package com.qtekfun.ultimatecamera.feature.settings

import com.qtekfun.ultimatecamera.core.audio.AudioInputKind
import com.qtekfun.ultimatecamera.core.audio.AudioSource
import com.qtekfun.ultimatecamera.core.settings.AppSettings
import com.qtekfun.ultimatecamera.core.settings.FeatureGroup
import com.qtekfun.ultimatecamera.core.settings.HdrPrefs
import com.qtekfun.ultimatecamera.core.settings.MulticamPrefs
import com.qtekfun.ultimatecamera.core.settings.PhotoHdrDefault
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import com.qtekfun.ultimatecamera.core.settings.StartMode
import com.qtekfun.ultimatecamera.core.settings.TimecodeMode
import com.qtekfun.ultimatecamera.core.storage.LocationMode
import com.qtekfun.ultimatecamera.core.storage.LocationProblem
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.RecordingStreams
import com.qtekfun.ultimatecamera.core.storage.WriteSpeedPolicy
import com.qtekfun.ultimatecamera.core.storage.WriteSpeedTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
class SettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = FakeSettings()
    private val storage = FakeStorage()
    private val volumeChanges = MutableSharedFlow<Unit>()
    private val devices = MutableStateFlow<List<AudioSource>>(emptyList())
    private var active: RecordingStreams? = null

    /** Small and deterministic: 4 MiB in 64 KiB writes, 1 ms per clock read. */
    private val speedTest = run {
        var now = 0L
        WriteSpeedTest(
            WriteSpeedTest.Config(maxBytes = 4L * MIB, minBytes = MIB.toLong(), bufferBytes = 64 * 1024, syncEveryBytes = MIB.toLong()),
            nanoTime = { now.also { now += 1_000_000 } }
        )
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: SettingsRepository = settings) = SettingsViewModel(
        repository = repository,
        backend = storage,
        volumeChanges = volumeChanges,
        audioDevices = devices,
        speedTest = speedTest,
        speedDispatcher = dispatcher,
        ioDispatcher = dispatcher,
        photoHdr = repository as? FakeSettings,
        activeStreams = { active }
    )

    private fun test(block: suspend TestScope.(SettingsViewModel) -> Unit) = runTest(dispatcher) {
        val vm = viewModel()
        block(vm)
    }

    @Test
    fun everySwitchReachesTheRepository() = test { vm ->
        vm.setFeatureGroupEnabled(FeatureGroup.LUTS, false)
        vm.setMaxBrightness(true)
        vm.setVolumeKeysRecord(false)
        vm.setAlertVibration(false)
        vm.setBackgroundRecording(false)
        vm.setThermalStepDown(true)
        vm.setAudioInput("usb:Mic")
        vm.setAudioGainDb(6f)
        vm.setSeparateWav(true)
        val s = settings.settings.value
        assertFalse(s.isEnabled(FeatureGroup.LUTS))
        assertTrue(s.maxBrightness)
        assertFalse(s.volumeKeysRecord)
        assertFalse(s.alertVibration)
        assertFalse(s.backgroundRecording)
        assertTrue(s.thermalStepDown)
        assertEquals("usb:Mic", s.audioInputKey)
        assertEquals(6f, s.audioGainDb)
        assertTrue(s.separateWav)
    }

    @Test
    fun optionalSectionsReadAndWriteTheirOwnStores() = test { vm ->
        val collectors = listOf(vm.settings, vm.captureModePrefs, vm.multicamPrefs, vm.secondCameraPair, vm.photoHdrDefault, vm.hdrPrefs)
            .map { flow -> backgroundScope.launch { flow.collect {} } }
        vm.setStartMode(StartMode.PHOTO)
        vm.setTimecodeMode(TimecodeMode.TIME_OF_DAY)
        vm.setMulticamPrefs(MulticamPrefs(recordProxy = true))
        vm.setSecondCameraPair("0+2")
        vm.setPhotoHdrDefault(PhotoHdrDefault.OFF)
        vm.setHdrPrefs(HdrPrefs(experimental = true))
        assertEquals(StartMode.PHOTO, vm.captureModePrefs.value?.startMode)
        assertEquals(TimecodeMode.TIME_OF_DAY, vm.captureModePrefs.value?.timecode)
        assertTrue(vm.multicamPrefs.value!!.recordProxy)
        assertEquals("0+2", vm.secondCameraPair.value)
        assertEquals(PhotoHdrDefault.OFF, vm.photoHdrDefault.value)
        assertTrue(vm.hdrPrefs.value!!.experimental)
        assertEquals(AppSettings.DEFAULT, vm.settings.value)
        collectors.forEach { it.cancel() }
    }

    @Test
    fun aPlainRepositoryHidesTheOptionalSections() = runTest(dispatcher) {
        val plain = object : SettingsRepository by settings {}
        val vm = viewModel(plain)
        listOf(vm.captureModePrefs, vm.multicamPrefs, vm.secondCameraPair, vm.photoHdrDefault, vm.hdrPrefs).forEach {
            backgroundScope.launch { it.collect {} }
            assertNull(it.value)
        }
        // Writes to sections the repository does not have are dropped, not a crash.
        vm.setStartMode(StartMode.PHOTO)
        vm.setHdrPrefs(HdrPrefs(experimental = true))
        assertEquals(StartMode.LAST_USED, settings.captureModePrefs.value.startMode)
    }

    @Test
    fun theLocationIsCheckedAtStartAndAfterAVolumeChange() = test { vm ->
        assertEquals(RecordingLocation.Default, vm.location.value?.effective)
        assertEquals(LocationMode.Automatic, vm.options.value?.mode)
        val drive = storage.addDrive("1a2b-3c4d", freeBytes = 8 * FakeStorage.GIB)
        volumeChanges.emit(Unit)
        // Automatic prefers the drive once it is plugged in.
        assertEquals(drive, vm.location.value?.effective)
        assertEquals(1, vm.options.value?.drives?.size)
    }

    @Test
    fun selectingThePhoneStoresTheMode() = test { vm ->
        storage.addDrive("1a2b-3c4d", freeBytes = 8 * FakeStorage.GIB)
        vm.selectLocation(LocationMode.Phone)
        assertEquals(LocationMode.Phone, vm.options.value?.mode)
        assertEquals(RecordingLocation.Default, vm.location.value?.effective)
        assertEquals(LocationMode.Phone.stored, settings.settings.value.recordingLocationMode)
    }

    @Test
    fun aGoodFolderBecomesTheLocation() = test { vm ->
        val folder = storage.offerFolder(USB_TREE, "1A2B-3C4D")
        vm.chooseFolder(USB_TREE)
        assertNull(vm.locationProblem.value)
        assertEquals(folder, vm.location.value?.effective)
        assertEquals(USB_TREE, settings.settings.value.recordingTreeUri)
    }

    @Test
    fun aRefusedFolderIsExplainedOnce() = test { vm ->
        vm.chooseFolder("content://com.google.android.apps.docs.storage/tree/abc")
        assertEquals(LocationProblem.UNSUPPORTED_PROVIDER, vm.locationProblem.value)
        vm.consumeLocationProblem()
        assertNull(vm.locationProblem.value)
        vm.chooseFolder(null) // Picker cancelled: nothing happens.
        assertNull(vm.locationProblem.value)
    }

    @Test
    fun resetDropsTheSettingsAndTheFolderGrant() = test { vm ->
        storage.offerFolder(USB_TREE, "1A2B-3C4D")
        vm.chooseFolder(USB_TREE)
        vm.setMaxBrightness(true)
        vm.resetToDefaults()
        assertEquals(1, settings.resets)
        assertTrue(storage.grants.isEmpty())
        assertEquals(RecordingLocation.Default, vm.location.value?.effective)
    }

    @Test
    fun theSpeedTestMeasuresAgainstTheDefaultStreams() = test { vm ->
        vm.runSpeedTest()
        advanceUntilIdle()
        val done = vm.speed.value as SpeedTestUi.Done
        val streams = RecordingStreams.uhd30TenBit(separateWav = false)
        assertEquals(WriteSpeedPolicy.requiredBitsPerSecond(streams) / 8e6, done.requiredMegabytesPerSecond, 1e-9)
        assertTrue(done.megabytesPerSecond > 0)
        assertTrue(storage.written.isEmpty()) // The test file is gone.
    }

    @Test
    fun theSpeedTestUsesTheOpenModeAndTheSeparateWav() = test { vm ->
        active = RecordingStreams(videoBitsPerSecond = 200_000_000)
        settings.setSeparateWav(true)
        vm.runSpeedTest()
        advanceUntilIdle()
        val done = vm.speed.value as SpeedTestUi.Done
        val expected = active!!.copy(separateWavBitsPerSecond = RecordingStreams.SEPARATE_WAV_BITS_PER_SECOND)
        assertEquals(WriteSpeedPolicy.requiredBitsPerSecond(expected) / 8e6, done.requiredMegabytesPerSecond, 1e-9)
    }

    @Test
    fun theSpeedTestReportsTooLittleSpaceAndFailures() = test { vm ->
        storage.free[RecordingLocation.Default] = 1024
        vm.runSpeedTest()
        assertEquals(SpeedTestUi.NotEnoughSpace, vm.speed.value)
        storage.free.clear()
        storage.failCreate = true
        vm.runSpeedTest()
        assertEquals(SpeedTestUi.Failed, vm.speed.value)
    }

    @Test
    fun theSpeedTestFailsWithNowhereToRecord() = test { vm ->
        storage.existing.clear()
        volumeChanges.emit(Unit)
        assertNull(vm.location.value?.effective)
        vm.runSpeedTest()
        assertEquals(SpeedTestUi.Failed, vm.speed.value)
    }

    @Test
    fun aVolumeChangeClearsTheSpeedResult() = test { vm ->
        vm.runSpeedTest()
        assertTrue(vm.speed.value is SpeedTestUi.Done)
        volumeChanges.emit(Unit)
        assertEquals(SpeedTestUi.Idle, vm.speed.value)
    }

    @Test
    fun theAudioInputFollowsDevicesAndTheStoredChoice() = test { vm ->
        backgroundScope.launch { vm.audioInput.collect {} }
        val builtin = AudioSource(1, AudioInputKind.BUILTIN, "Phone")
        val usb = AudioSource(2, AudioInputKind.USB, "Rode")
        devices.value = listOf(builtin, usb)
        assertEquals(usb, vm.audioInput.value.effective)
        vm.setAudioInput(builtin.key)
        assertEquals(builtin, vm.audioInput.value.effective)
        assertFalse(vm.audioInput.value.pickMissing)
        devices.value = listOf(usb)
        assertTrue(vm.audioInput.value.pickMissing)
        assertEquals(settings.settings.first().audioInputKey, vm.audioInput.value.storedKey)
    }

    @Test
    fun controlOptionsMapOneToOne() = test { vm ->
        // Each switch writes its own key only.
        vm.setThermalStepDown(true)
        assertEquals(AppSettings.DEFAULT.copy(thermalStepDown = true), settings.settings.value)
    }

    private companion object {
        const val MIB = 1024 * 1024
        const val USB_TREE = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AFootage"
    }
}
