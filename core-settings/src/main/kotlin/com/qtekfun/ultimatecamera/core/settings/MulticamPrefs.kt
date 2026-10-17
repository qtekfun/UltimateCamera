package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.Flow

/**
 * Multicamera options of a take (Phase 6, D59), both off by default.
 *
 * @property recordProxy also write a small H.264 copy `<base>_proxy.mp4` next to each take (REQ-NET-006).
 * @property concurrentCamera experimental: record a second local camera as its own file, where the phone reports
 *   concurrent camera combinations (REQ-NET-001).
 */
data class MulticamPrefs(val recordProxy: Boolean = false, val concurrentCamera: Boolean = false)

/** Implemented by [DataStoreSettingsRepository]; kept apart from [SettingsRepository] so fakes need not change. */
interface MulticamRepository {
    val multicamPrefs: Flow<MulticamPrefs>

    suspend fun setMulticamPrefs(prefs: MulticamPrefs)
}

/** Additive optional keys: their absence means the defaults, so the schema version does not change. */
internal object MulticamKeys {
    val RECORD_PROXY = booleanPreferencesKey("record_proxy")
    val CONCURRENT_CAMERA = booleanPreferencesKey("concurrent_camera")

    fun read(preferences: Preferences): MulticamPrefs = MulticamPrefs(
        recordProxy = preferences.asMap()[RECORD_PROXY] as? Boolean ?: false,
        concurrentCamera = preferences.asMap()[CONCURRENT_CAMERA] as? Boolean ?: false
    )

    fun write(preferences: MutablePreferences, prefs: MulticamPrefs) {
        preferences[RECORD_PROXY] = prefs.recordProxy
        preferences[CONCURRENT_CAMERA] = prefs.concurrentCamera
    }
}
