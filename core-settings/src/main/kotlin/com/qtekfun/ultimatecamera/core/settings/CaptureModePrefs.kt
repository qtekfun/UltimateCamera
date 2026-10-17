package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow

/** What the shutter does. [key] is persisted: never rename it. */
enum class ShutterMode(val key: String) {
    VIDEO("video"),
    PHOTO("photo")
}

/** The mode the camera opens in when the app starts (owner request). [key] is persisted. */
enum class StartMode(val key: String) {
    LAST_USED("last"),
    VIDEO("video"),
    PHOTO("photo")
}

/** Start-mode preference plus the mode last used, so [modeAtStart] can honour "last used". Defaults: last used, video. */
data class CaptureModePrefs(val startMode: StartMode = StartMode.LAST_USED, val lastMode: ShutterMode = ShutterMode.VIDEO) {
    val modeAtStart: ShutterMode
        get() = when (startMode) {
            StartMode.LAST_USED -> lastMode
            StartMode.VIDEO -> ShutterMode.VIDEO
            StartMode.PHOTO -> ShutterMode.PHOTO
        }
}

/** Implemented by [DataStoreSettingsRepository]; kept apart from [SettingsRepository] so fakes need not change. */
interface CaptureModeRepository {
    val captureModePrefs: Flow<CaptureModePrefs>

    suspend fun setStartMode(mode: StartMode)

    /** Remembers the mode the user switched to (for [StartMode.LAST_USED]). */
    suspend fun setLastMode(mode: ShutterMode)
}

/** Additive optional keys: their absence means the defaults, so the schema version does not change. */
internal object CaptureModeKeys {
    val START_MODE = stringPreferencesKey("start_mode")
    val LAST_MODE = stringPreferencesKey("last_shutter_mode")

    fun read(preferences: Preferences): CaptureModePrefs = CaptureModePrefs(
        startMode = StartMode.entries.firstOrNull { it.key == preferences[START_MODE] } ?: StartMode.LAST_USED,
        lastMode = ShutterMode.entries.firstOrNull { it.key == preferences[LAST_MODE] } ?: ShutterMode.VIDEO
    )

    fun writeStart(preferences: MutablePreferences, mode: StartMode) {
        preferences[START_MODE] = mode.key
    }

    fun writeLast(preferences: MutablePreferences, mode: ShutterMode) {
        preferences[LAST_MODE] = mode.key
    }
}
