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

/**
 * What the camera screen's timecode shows (D58). [key] is persisted. Record run: the take's running time from
 * 00:00:00:00. Time of day: the time-of-day timecode the takes are stamped with (the session's common clock when
 * connected, else the phone clock), running also between takes.
 */
enum class TimecodeMode(val key: String) {
    RECORD_RUN("record_run"),
    TIME_OF_DAY("time_of_day")
}

/**
 * Start-mode preference plus the mode last used, so [modeAtStart] can honour "last used", and the timecode display.
 * Defaults: last used, video, record run.
 */
data class CaptureModePrefs(
    val startMode: StartMode = StartMode.LAST_USED,
    val lastMode: ShutterMode = ShutterMode.VIDEO,
    val timecode: TimecodeMode = TimecodeMode.RECORD_RUN
) {
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

    suspend fun setTimecodeMode(mode: TimecodeMode)
}

/** Additive optional keys: their absence means the defaults, so the schema version does not change. */
internal object CaptureModeKeys {
    val START_MODE = stringPreferencesKey("start_mode")
    val LAST_MODE = stringPreferencesKey("last_shutter_mode")
    val TIMECODE_MODE = stringPreferencesKey("timecode_mode")

    fun read(preferences: Preferences): CaptureModePrefs = CaptureModePrefs(
        startMode = StartMode.entries.firstOrNull { it.key == preferences[START_MODE] } ?: StartMode.LAST_USED,
        lastMode = ShutterMode.entries.firstOrNull { it.key == preferences[LAST_MODE] } ?: ShutterMode.VIDEO,
        timecode = TimecodeMode.entries.firstOrNull { it.key == preferences[TIMECODE_MODE] } ?: TimecodeMode.RECORD_RUN
    )

    fun writeStart(preferences: MutablePreferences, mode: StartMode) {
        preferences[START_MODE] = mode.key
    }

    fun writeLast(preferences: MutablePreferences, mode: ShutterMode) {
        preferences[LAST_MODE] = mode.key
    }

    fun writeTimecode(preferences: MutablePreferences, mode: TimecodeMode) {
        preferences[TIMECODE_MODE] = mode.key
    }
}
