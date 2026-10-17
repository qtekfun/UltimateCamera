package com.qtekfun.ultimatecamera.core.settings

import kotlinx.coroutines.flow.Flow

/** Persisted app settings. Every write goes to disk before the call returns. */
interface SettingsRepository {
    /** The current settings, then every change. Never fails: unreadable data yields [AppSettings.DEFAULT]. */
    val settings: Flow<AppSettings>

    suspend fun setFeatureGroupEnabled(group: FeatureGroup, enabled: Boolean)

    suspend fun setMaxBrightness(enabled: Boolean)

    /** Stores the recording folder's SAF tree URI; null goes back to the default location. */
    suspend fun setRecordingTreeUri(uri: String?)

    /** Stores the recording look token (REQ-COL-004); null = none. */
    suspend fun setRecordingLook(token: String?)

    /** Stores the monitor-only look token (REQ-COL-004); null = automatic. */
    suspend fun setMonitorLook(token: String?)

    /** The picked audio input's stable key (REQ-AUD-001); null goes back to the automatic choice. */
    suspend fun setAudioInputKey(key: String?)

    /** Manual input gain in dB, clamped to [AppSettings.AUDIO_GAIN_MIN_DB]..[AppSettings.AUDIO_GAIN_MAX_DB]. */
    suspend fun setAudioGainDb(gainDb: Float)

    /** Separate WAV file next to the MP4 (REQ-AUD-003). */
    suspend fun setSeparateWav(enabled: Boolean)

    /** Drops every stored value; [settings] then emits [AppSettings.DEFAULT]. */
    suspend fun resetToDefaults()
}
