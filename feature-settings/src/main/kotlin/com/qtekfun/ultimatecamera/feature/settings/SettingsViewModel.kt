package com.qtekfun.ultimatecamera.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.audio.AudioInputChoice
import com.qtekfun.ultimatecamera.core.audio.AudioInputs
import com.qtekfun.ultimatecamera.core.audio.AudioSource
import com.qtekfun.ultimatecamera.core.settings.AppSettings
import com.qtekfun.ultimatecamera.core.settings.CaptureModePrefs
import com.qtekfun.ultimatecamera.core.settings.CaptureModeRepository
import com.qtekfun.ultimatecamera.core.settings.ControlOption
import com.qtekfun.ultimatecamera.core.settings.FeatureGroup
import com.qtekfun.ultimatecamera.core.settings.HdrPrefs
import com.qtekfun.ultimatecamera.core.settings.HdrRepository
import com.qtekfun.ultimatecamera.core.settings.MulticamPrefs
import com.qtekfun.ultimatecamera.core.settings.MulticamRepository
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import com.qtekfun.ultimatecamera.core.settings.StartMode
import com.qtekfun.ultimatecamera.core.settings.TimecodeMode
import com.qtekfun.ultimatecamera.core.storage.ActiveRecordingStreams
import com.qtekfun.ultimatecamera.core.storage.AndroidStorageBackend
import com.qtekfun.ultimatecamera.core.storage.LocationCheck
import com.qtekfun.ultimatecamera.core.storage.LocationMode
import com.qtekfun.ultimatecamera.core.storage.LocationOptions
import com.qtekfun.ultimatecamera.core.storage.LocationProblem
import com.qtekfun.ultimatecamera.core.storage.LocationStatus
import com.qtekfun.ultimatecamera.core.storage.RecordingLocationRepository
import com.qtekfun.ultimatecamera.core.storage.RecordingStreams
import com.qtekfun.ultimatecamera.core.storage.SettingsRecordingLocationStore
import com.qtekfun.ultimatecamera.core.storage.SpeedTestOutcome
import com.qtekfun.ultimatecamera.core.storage.SpeedVerdict
import com.qtekfun.ultimatecamera.core.storage.WriteSpeedPolicy
import com.qtekfun.ultimatecamera.core.storage.WriteSpeedTest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The write-speed test as the Settings screen shows it (REQ-STO-001). */
sealed interface SpeedTestUi {
    data object Idle : SpeedTestUi

    data class Running(val progress: Float) : SpeedTestUi

    /** [requiredMegabytesPerSecond] is what the profile needs with the OK margin (×2, D28). */
    data class Done(val megabytesPerSecond: Double, val requiredMegabytesPerSecond: Double, val verdict: SpeedVerdict) : SpeedTestUi

    data object NotEnoughSpace : SpeedTestUi

    data object Failed : SpeedTestUi
}

/** The audio input part of Settings (REQ-AUD-001): what is connected and what a take would record from. */
data class AudioInputUi(val available: List<AudioSource> = emptyList(), val storedKey: String? = null) {
    val effective: AudioSource? get() = AudioInputChoice.resolve(available, storedKey)
    val pickMissing: Boolean get() = AudioInputChoice.isMissing(available, storedKey)
}

/**
 * Settings screen state: the persisted [AppSettings] (null until the first read from disk) and their setters, the
 * recording location with its write-speed test (REQ-STO-001) and the audio input list (REQ-AUD-001).
 */
class SettingsViewModel(application: Application, private val repository: SettingsRepository) : AndroidViewModel(application) {
    private val backend = AndroidStorageBackend(application)
    private val locations = RecordingLocationRepository(SettingsRecordingLocationStore(repository), backend)
    private val inputs = AudioInputs(application)
    private var speedJob: Job? = null

    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _location = MutableStateFlow<LocationCheck?>(null)

    /** The chosen location and where takes go now; null while checking. */
    val location: StateFlow<LocationCheck?> = _location.asStateFlow()

    private val _options = MutableStateFlow<LocationOptions?>(null)

    /** The location choices (D50): Automatic, phone, drives, the picked folder. */
    val options: StateFlow<LocationOptions?> = _options.asStateFlow()

    private val _locationProblem = MutableStateFlow<LocationProblem?>(null)

    /** Why the last folder pick was refused; cleared by [consumeLocationProblem]. */
    val locationProblem: StateFlow<LocationProblem?> = _locationProblem.asStateFlow()

    private val _speed = MutableStateFlow<SpeedTestUi>(SpeedTestUi.Idle)
    val speed: StateFlow<SpeedTestUi> = _speed.asStateFlow()

    val audioInput: StateFlow<AudioInputUi> = combine(inputs.changes(), repository.settings) { devices, s -> AudioInputUi(devices, s.audioInputKey) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AudioInputUi())

    init {
        viewModelScope.launch { refreshLocation() }
        // A USB SSD plugged in or pulled out: re-check, and a speed result for another drive no longer applies.
        viewModelScope.launch {
            backend.volumeChanges().collect {
                cancelSpeedTest()
                refreshLocation()
            }
        }
    }

    fun setFeatureGroupEnabled(group: FeatureGroup, enabled: Boolean) {
        viewModelScope.launch { repository.setFeatureGroupEnabled(group, enabled) }
    }

    fun setMaxBrightness(enabled: Boolean) {
        viewModelScope.launch { repository.setMaxBrightness(enabled) }
    }

    fun setVolumeKeysRecord(enabled: Boolean) {
        viewModelScope.launch { repository.setControlOption(ControlOption.VOLUME_KEYS_RECORD, enabled) }
    }

    fun setAlertVibration(enabled: Boolean) {
        viewModelScope.launch { repository.setControlOption(ControlOption.ALERT_VIBRATION, enabled) }
    }

    private val modes = repository as? CaptureModeRepository

    /** Null when the repository does not store modes (test fakes): the row is then hidden. */
    val captureModePrefs: StateFlow<CaptureModePrefs?> = (modes?.captureModePrefs ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val multicam = repository as? MulticamRepository

    /** Proxy and second-camera options (D59); null when the repository does not store them (the section is hidden). */
    val multicamPrefs: StateFlow<MulticamPrefs?> = (multicam?.multicamPrefs ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setMulticamPrefs(prefs: MulticamPrefs) {
        viewModelScope.launch { multicam?.setMulticamPrefs(prefs) }
    }

    private val hdr = repository as? HdrRepository

    /** HDR viewfinder and the Experimental switch (D37, D63); null when the repository does not store them. */
    val hdrPrefs: StateFlow<HdrPrefs?> = (hdr?.hdrPrefs ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setHdrPrefs(prefs: HdrPrefs) {
        viewModelScope.launch { hdr?.setHdrPrefs(prefs) }
    }

    fun setStartMode(mode: StartMode) {
        viewModelScope.launch { modes?.setStartMode(mode) }
    }

    fun setTimecodeMode(mode: TimecodeMode) {
        viewModelScope.launch { modes?.setTimecodeMode(mode) }
    }

    /** The folder picker's result (null: cancelled). */
    fun chooseFolder(treeUri: String?) {
        if (treeUri == null) return
        viewModelScope.launch {
            cancelSpeedTest()
            val status = locations.choose(treeUri)
            if (status is LocationStatus.Unavailable) _locationProblem.value = status.problem
            refreshLocation()
        }
    }

    fun selectLocation(mode: LocationMode) {
        viewModelScope.launch {
            cancelSpeedTest()
            locations.select(mode)
            refreshLocation()
        }
    }

    fun consumeLocationProblem() {
        _locationProblem.value = null
    }

    /** Runs the write-speed test where the next take would go, against the open camera mode's bitrate. */
    fun runSpeedTest() {
        if (speedJob?.isActive == true) return
        speedJob = viewModelScope.launch {
            val target = (_location.value ?: refreshLocation()).effective
            if (target == null) {
                _speed.value = SpeedTestUi.Failed
                return@launch
            }
            _speed.value = SpeedTestUi.Running(0f)
            val outcome = WriteSpeedTest().run(backend, target, AndroidStorageBackend.lowPriorityDispatcher) { fraction ->
                _speed.update { if (it is SpeedTestUi.Running) SpeedTestUi.Running(fraction) else it }
            }
            _speed.value = when (outcome) {
                is SpeedTestOutcome.Measured -> {
                    val streams = currentStreams()
                    SpeedTestUi.Done(
                        megabytesPerSecond = outcome.result.megabytesPerSecond,
                        requiredMegabytesPerSecond = WriteSpeedPolicy.requiredBitsPerSecond(streams) / BITS_PER_MEGABYTE,
                        verdict = WriteSpeedPolicy.classify(outcome.result, streams)
                    )
                }
                is SpeedTestOutcome.NotEnoughSpace -> SpeedTestUi.NotEnoughSpace
                is SpeedTestOutcome.Failed -> SpeedTestUi.Failed
            }
        }
    }

    fun setAudioInput(key: String?) {
        viewModelScope.launch { repository.setAudioInputKey(key) }
    }

    fun setAudioGainDb(gainDb: Float) {
        viewModelScope.launch { repository.setAudioGainDb(gainDb) }
    }

    fun setSeparateWav(enabled: Boolean) {
        viewModelScope.launch { repository.setSeparateWav(enabled) }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            cancelSpeedTest()
            repository.resetToDefaults()
            locations.forgetFolder() // The reset dropped the folder; release its grant too.
            refreshLocation()
        }
    }

    /** The open camera mode's streams, or the default 4K30 10-bit one; the separate WAV counts when it is on. */
    private suspend fun currentStreams(): RecordingStreams {
        val separateWav = repository.settings.first().separateWav
        val active = ActiveRecordingStreams.current.value ?: return RecordingStreams.uhd30TenBit(separateWav)
        return active.copy(separateWavBitsPerSecond = if (separateWav) RecordingStreams.SEPARATE_WAV_BITS_PER_SECOND else 0)
    }

    private suspend fun refreshLocation(): LocationCheck {
        _options.value = locations.options()
        return locations.check().also { _location.value = it }
    }

    private fun cancelSpeedTest() {
        speedJob?.cancel()
        _speed.value = SpeedTestUi.Idle
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val BITS_PER_MEGABYTE = 8e6
    }
}
