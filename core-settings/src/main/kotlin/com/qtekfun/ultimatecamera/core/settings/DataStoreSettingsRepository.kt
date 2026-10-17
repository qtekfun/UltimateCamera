package com.qtekfun.ultimatecamera.core.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [SettingsRepository] on a Preferences DataStore file (`files/datastore/settings.preferences_pb`).
 *
 * Robustness: a file that cannot be parsed is replaced by an empty one (defaults); a read error yields defaults; a
 * value of an unexpected type is ignored and its default used; unknown keys (e.g. from a newer version) are kept
 * untouched. Every write also stores [SCHEMA_VERSION] so a later version, or a restored backup (REQ-BAK-002), knows
 * which layout it reads.
 *
 * DataStore allows one instance per file per process: use [getInstance] in the app.
 */
class DataStoreSettingsRepository internal constructor(internal val dataStore: DataStore<Preferences>) :
    SettingsRepository,
    MonitorPrefsRepository,
    CaptureModeRepository {
    override val settings: Flow<AppSettings> =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map(::toAppSettings)
            .distinctUntilChanged()

    override suspend fun setFeatureGroupEnabled(group: FeatureGroup, enabled: Boolean) {
        write { it[groupKey(group)] = enabled }
    }

    override suspend fun setMaxBrightness(enabled: Boolean) {
        write { it[MAX_BRIGHTNESS] = enabled }
    }

    override suspend fun setControlOption(option: ControlOption, enabled: Boolean) {
        val key = when (option) {
            ControlOption.VOLUME_KEYS_RECORD -> VOLUME_KEYS_RECORD
            ControlOption.ALERT_VIBRATION -> ALERT_VIBRATION
        }
        write { it[key] = enabled }
    }

    override suspend fun setRecordingLocationMode(mode: String?) {
        write { if (mode.isNullOrBlank()) it.remove(RECORDING_LOCATION_MODE) else it[RECORDING_LOCATION_MODE] = mode }
    }

    override suspend fun setRecordingTreeUri(uri: String?) {
        write { if (uri.isNullOrBlank()) it.remove(RECORDING_TREE_URI) else it[RECORDING_TREE_URI] = uri }
    }

    override suspend fun setAutoConfigOffered(offered: Boolean) {
        write { it[AUTO_CONFIG_OFFERED] = offered }
    }

    override suspend fun setLook(slot: LookSlot, token: String?) {
        val key = if (slot == LookSlot.RECORDING) RECORDING_LOOK else MONITOR_LOOK
        write { if (token.isNullOrBlank()) it.remove(key) else it[key] = token }
    }

    override suspend fun setAudioInputKey(key: String?) {
        write { if (key.isNullOrBlank()) it.remove(AUDIO_INPUT_KEY) else it[AUDIO_INPUT_KEY] = key }
    }

    override suspend fun setAudioGainDb(gainDb: Float) {
        write { it[AUDIO_GAIN_DB] = clampGain(gainDb) }
    }

    override suspend fun setSeparateWav(enabled: Boolean) {
        write { it[SEPARATE_WAV] = enabled }
    }

    override val monitorPrefs: Flow<MonitorPrefs> =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map(MonitorPrefsKeys::read)
            .distinctUntilChanged()

    override suspend fun setMonitorPrefs(prefs: MonitorPrefs) {
        write { MonitorPrefsKeys.write(it, prefs) }
    }

    override val captureModePrefs: Flow<CaptureModePrefs> =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map(CaptureModeKeys::read)
            .distinctUntilChanged()

    override suspend fun setStartMode(mode: StartMode) {
        write { CaptureModeKeys.writeStart(it, mode) }
    }

    override suspend fun setLastMode(mode: ShutterMode) {
        write { CaptureModeKeys.writeLast(it, mode) }
    }

    override suspend fun resetToDefaults() {
        write { it.clear() }
    }

    /** The layout version stored on disk, or null if nothing was ever written. */
    internal suspend fun storedSchemaVersion(): Int? = dataStore.data.first().asMap()[SCHEMA] as? Int

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        dataStore.edit { preferences ->
            block(preferences)
            preferences[SCHEMA] = SCHEMA_VERSION
        }
    }

    companion object {
        /**
         * Layout of the stored keys. Bump it and add a [DataMigration] in [create] when keys change meaning. A new
         * optional key whose absence means its default (e.g. [RECORDING_TREE_URI]) is not a layout change: older files
         * simply lack it, and older versions keep it untouched as an unknown key.
         */
        const val SCHEMA_VERSION = 1

        internal const val FILE_NAME = "settings"

        internal val SCHEMA = intPreferencesKey("schema_version")
        internal val MAX_BRIGHTNESS = booleanPreferencesKey("max_brightness")
        internal val RECORDING_TREE_URI = stringPreferencesKey("recording_tree_uri")
        internal val AUTO_CONFIG_OFFERED = booleanPreferencesKey("auto_config_offered")
        internal val RECORDING_LOOK = stringPreferencesKey("recording_look")
        internal val MONITOR_LOOK = stringPreferencesKey("monitor_look")
        internal val VOLUME_KEYS_RECORD = booleanPreferencesKey("volume_keys_record")
        internal val ALERT_VIBRATION = booleanPreferencesKey("alert_vibration")
        internal val RECORDING_LOCATION_MODE = stringPreferencesKey("recording_location_mode")
        internal val AUDIO_INPUT_KEY = stringPreferencesKey("audio_input_key")
        internal val AUDIO_GAIN_DB = floatPreferencesKey("audio_gain_db")
        internal val SEPARATE_WAV = booleanPreferencesKey("separate_wav")

        /** NaN (a corrupt value) is the default gain; anything else is clamped to the allowed range. */
        internal fun clampGain(gainDb: Float): Float =
            if (gainDb.isNaN()) AppSettings.DEFAULT.audioGainDb else gainDb.coerceIn(AppSettings.AUDIO_GAIN_MIN_DB, AppSettings.AUDIO_GAIN_MAX_DB)

        internal fun groupKey(group: FeatureGroup) = booleanPreferencesKey("group_${group.key}")

        @Volatile
        private var instance: DataStoreSettingsRepository? = null

        /** The process-wide repository, stored in the app's private files. */
        fun getInstance(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                create(
                    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                    produceFile = { appContext.preferencesDataStoreFile(FILE_NAME) }
                ).also { instance = it }
            }
        }

        /** A repository on [produceFile] (must end in `.preferences_pb`). Only one may be active per file. */
        internal fun create(scope: CoroutineScope, produceFile: () -> File): DataStoreSettingsRepository = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                migrations = listOf(SchemaVersionMigration),
                scope = scope,
                produceFile = produceFile
            )
        )

        internal fun toAppSettings(preferences: Preferences): AppSettings {
            val stored = preferences.asMap()
            return AppSettings(
                enabledGroups = FeatureGroup.entries.filterTo(mutableSetOf()) { group ->
                    stored[groupKey(group)] as? Boolean ?: group.defaultEnabled
                },
                maxBrightness = stored[MAX_BRIGHTNESS] as? Boolean ?: AppSettings.DEFAULT.maxBrightness,
                recordingTreeUri = (stored[RECORDING_TREE_URI] as? String)?.takeIf { it.isNotBlank() },
                autoConfigOffered = stored[AUTO_CONFIG_OFFERED] as? Boolean ?: AppSettings.DEFAULT.autoConfigOffered,
                recordingLook = (stored[RECORDING_LOOK] as? String)?.takeIf { it.isNotBlank() },
                monitorLook = (stored[MONITOR_LOOK] as? String)?.takeIf { it.isNotBlank() },
                volumeKeysRecord = stored[VOLUME_KEYS_RECORD] as? Boolean ?: AppSettings.DEFAULT.volumeKeysRecord,
                alertVibration = stored[ALERT_VIBRATION] as? Boolean ?: AppSettings.DEFAULT.alertVibration,
                recordingLocationMode = (stored[RECORDING_LOCATION_MODE] as? String)?.takeIf { it.isNotBlank() },
                audioInputKey = (stored[AUDIO_INPUT_KEY] as? String)?.takeIf { it.isNotBlank() },
                audioGainDb = (stored[AUDIO_GAIN_DB] as? Float)?.let(::clampGain) ?: AppSettings.DEFAULT.audioGainDb,
                separateWav = stored[SEPARATE_WAV] as? Boolean ?: AppSettings.DEFAULT.separateWav
            )
        }
    }

    /**
     * Stamps files written before the schema key existed (or with a value of the wrong type). Future layout changes
     * add their own migration from the previous version here; a file from a newer version is left alone.
     */
    private object SchemaVersionMigration : DataMigration<Preferences> {
        override suspend fun shouldMigrate(currentData: Preferences): Boolean {
            val version = currentData.asMap()[SCHEMA] as? Int
            return currentData.asMap().isNotEmpty() && (version == null || version < SCHEMA_VERSION)
        }

        override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply { this[SCHEMA] = SCHEMA_VERSION }

        override suspend fun cleanUp() = Unit
    }
}
