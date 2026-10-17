package com.qtekfun.ultimatecamera.core.settings

import kotlinx.coroutines.flow.Flow

/** One-time prompts shown at first start (kept apart from the user's preferences). */
interface FirstStartFlags {
    /** Records that the first-start auto-configuration (D42) was run or skipped. */
    suspend fun setAutoConfigOffered(offered: Boolean)
}

/** Persisted app settings. Every write goes to disk before the call returns. */
interface SettingsRepository : FirstStartFlags {
    /** The current settings, then every change. Never fails: unreadable data yields [AppSettings.DEFAULT]. */
    val settings: Flow<AppSettings>

    suspend fun setFeatureGroupEnabled(group: FeatureGroup, enabled: Boolean)

    suspend fun setMaxBrightness(enabled: Boolean)

    /** Switches one of the camera screen's on/off controls ([AppSettings.volumeKeysRecord], [AppSettings.alertVibration]). */
    suspend fun setControlOption(option: ControlOption, enabled: Boolean)

    /** Stores the recording folder's SAF tree URI; null goes back to the default location. */
    suspend fun setRecordingTreeUri(uri: String?)

    /** Stores a look token (REQ-COL-004) for [slot]; null = none (recording) or automatic (monitor). */
    suspend fun setLook(slot: LookSlot, token: String?)

    /** Stores where takes go (core-storage's `LocationMode` text); null goes back to the default (automatic). */
    suspend fun setRecordingLocationMode(mode: String?)

    /** The picked audio input's stable key (REQ-AUD-001); null goes back to the automatic choice. */
    suspend fun setAudioInputKey(key: String?)

    /** Manual input gain in dB, clamped to [AppSettings.AUDIO_GAIN_MIN_DB]..[AppSettings.AUDIO_GAIN_MAX_DB]. */
    suspend fun setAudioGainDb(gainDb: Float)

    /** Separate WAV file next to the MP4 (REQ-AUD-003). */
    suspend fun setSeparateWav(enabled: Boolean)

    /** Drops every stored value; [settings] then emits [AppSettings.DEFAULT]. */
    suspend fun resetToDefaults()
}

/** On/off controls of the camera screen, stored under their own keys (D47, D52). */
enum class ControlOption {
    /** Volume keys start and stop recording. */
    VOLUME_KEYS_RECORD,

    /** A short vibration on the first critical health alert. */
    ALERT_VIBRATION,

    /** Step down one mode at the next take when the heat forecast predicted SEVERE (D68). */
    THERMAL_STEP_DOWN
}

/** The two looks of the camera (REQ-COL-004): baked into the recording, or shown only on the monitor. */
enum class LookSlot {
    RECORDING,
    MONITOR
}
