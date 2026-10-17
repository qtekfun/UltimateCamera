package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

/** One stored setting with its DataStore type (REQ-BAK-002): the backup writes these as typed JSON, never the protobuf. */
sealed interface SettingValue {
    data class Bool(val value: Boolean) : SettingValue

    data class IntValue(val value: Int) : SettingValue

    data class LongValue(val value: Long) : SettingValue

    data class FloatValue(val value: Float) : SettingValue

    data class DoubleValue(val value: Double) : SettingValue

    data class Text(val value: String) : SettingValue

    data class TextSet(val value: Set<String>) : SettingValue
}

/**
 * Raw access to every key of the settings DataStore for the backup (D65). Kept apart from [SettingsRepository] (at
 * detekt's function limit) and its fakes.
 */
interface SettingsBackupAccess {
    /** The layout version of the keys ([DataStoreSettingsRepository.SCHEMA_VERSION]). */
    val schemaVersion: Int

    /** Every stored key with its typed value, the schema key excluded. Keys of unknown types are left out. */
    suspend fun exportValues(): Map<String, SettingValue>

    /**
     * Writes [values]. With [replace] every stored key is removed first; otherwise the values are merged over the stored
     * ones. Keys in [DEVICE_SPECIFIC_KEYS] are never written and, on this phone, never removed. Returns the keys skipped.
     */
    suspend fun restoreValues(values: Map<String, SettingValue>, replace: Boolean): Set<String>

    companion object {
        /**
         * Values that only mean something on the phone that stored them: the SAF folder (its permission does not travel),
         * the location mode that may name it or a volume, the audio input, the camera ids of the second-camera pair and
         * the "auto-configuration was offered" flag (a new phone must measure itself).
         */
        val DEVICE_SPECIFIC_KEYS: Set<String> = setOf(
            "recording_tree_uri",
            "recording_location_mode",
            "audio_input_key",
            "second_camera_pair",
            "auto_config_offered"
        )
    }
}

/** The backup view of the DataStore repository, or null for repositories that are not DataStore ones. */
val SettingsRepository.backup: SettingsBackupAccess?
    get() = (this as? DataStoreSettingsRepository)?.let(::DataStoreSettingsBackup)

internal class DataStoreSettingsBackup(private val settings: DataStoreSettingsRepository) : SettingsBackupAccess {
    override val schemaVersion: Int = DataStoreSettingsRepository.SCHEMA_VERSION

    override suspend fun exportValues(): Map<String, SettingValue> = toValues(settings.dataStore.data.first())

    override suspend fun restoreValues(values: Map<String, SettingValue>, replace: Boolean): Set<String> {
        val skipped = values.keys.filterTo(sortedSetOf()) { it in SettingsBackupAccess.DEVICE_SPECIFIC_KEYS || it == SCHEMA_KEY }
        settings.dataStore.edit { preferences ->
            if (replace) {
                preferences.asMap().keys.filter { it.name !in SettingsBackupAccess.DEVICE_SPECIFIC_KEYS }.forEach { preferences.removeAny(it) }
            }
            values.filterKeys { it !in skipped }.forEach { (key, value) -> put(preferences, key, value) }
            preferences[DataStoreSettingsRepository.SCHEMA] = DataStoreSettingsRepository.SCHEMA_VERSION
        }
        return skipped
    }

    internal companion object {
        val SCHEMA_KEY = DataStoreSettingsRepository.SCHEMA.name

        fun toValues(preferences: Preferences): Map<String, SettingValue> = preferences.asMap()
            .filterKeys { it.name != SCHEMA_KEY }
            .mapNotNull { (key, value) -> valueOf(value)?.let { key.name to it } }
            .toMap(sortedMapOf())

        private fun valueOf(value: Any): SettingValue? = when (value) {
            is Boolean -> SettingValue.Bool(value)
            is Int -> SettingValue.IntValue(value)
            is Long -> SettingValue.LongValue(value)
            is Float -> SettingValue.FloatValue(value)
            is Double -> SettingValue.DoubleValue(value)
            is String -> SettingValue.Text(value)
            is Set<*> -> SettingValue.TextSet(value.filterIsInstance<String>().toSet())
            else -> null
        }

        fun put(preferences: MutablePreferences, key: String, value: SettingValue) {
            // The same name stored with another type would make DataStore keep both; drop any old entry first.
            preferences.asMap().keys.filter { it.name == key }.forEach { preferences.removeAny(it) }
            when (value) {
                is SettingValue.Bool -> preferences[booleanPreferencesKey(key)] = value.value
                is SettingValue.IntValue -> preferences[intPreferencesKey(key)] = value.value
                is SettingValue.LongValue -> preferences[longPreferencesKey(key)] = value.value
                is SettingValue.FloatValue -> preferences[floatPreferencesKey(key)] = value.value
                is SettingValue.DoubleValue -> preferences[doublePreferencesKey(key)] = value.value
                is SettingValue.Text -> preferences[stringPreferencesKey(key)] = value.value
                is SettingValue.TextSet -> preferences[stringSetPreferencesKey(key)] = value.value
            }
        }

        @Suppress("UNCHECKED_CAST") // remove() only uses the key's name.
        private fun MutablePreferences.removeAny(key: Preferences.Key<*>) {
            remove(key as Preferences.Key<Any>)
        }
    }
}
