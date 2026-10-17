package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The default styles (D74) as opaque `style:…` tokens (parsed by core-gpu's `StyleParams`; null = Standard), and whether
 * photos also keep an unstyled copy.
 */
data class StyleDefaults(val photo: String? = null, val video: String? = null, val keepBase: Boolean = false)

/** Settings › Styles. Kept apart from [SettingsRepository] (at detekt's function limit) and its fakes. */
interface StyleRepository {
    val styleDefaults: Flow<StyleDefaults>

    suspend fun setPhotoStyle(token: String?)

    suspend fun setVideoStyle(token: String?)

    suspend fun setKeepBase(keep: Boolean)
}

/** The style keys in the settings DataStore; additive and optional (absent = Standard, no copy), so the schema stays 1. */
class DataStoreStyleRepository internal constructor(private val settings: DataStoreSettingsRepository) : StyleRepository {
    override val styleDefaults: Flow<StyleDefaults> =
        settings.dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map(::read)
            .distinctUntilChanged()

    override suspend fun setPhotoStyle(token: String?) = put(PHOTO, token)

    override suspend fun setVideoStyle(token: String?) = put(VIDEO, token)

    override suspend fun setKeepBase(keep: Boolean) {
        settings.dataStore.edit {
            it[KEEP_BASE] = keep
            it[DataStoreSettingsRepository.SCHEMA] = DataStoreSettingsRepository.SCHEMA_VERSION
        }
    }

    private suspend fun put(key: Preferences.Key<String>, token: String?) {
        settings.dataStore.edit {
            if (token == null) it.remove(key) else it[key] = token
            it[DataStoreSettingsRepository.SCHEMA] = DataStoreSettingsRepository.SCHEMA_VERSION
        }
    }

    internal companion object {
        val PHOTO = stringPreferencesKey("photo_style")
        val VIDEO = stringPreferencesKey("video_style")
        val KEEP_BASE = booleanPreferencesKey("photo_style_keep_base")

        fun read(preferences: Preferences): StyleDefaults {
            val map = preferences.asMap()
            return StyleDefaults(map[PHOTO] as? String, map[VIDEO] as? String, map[KEEP_BASE] as? Boolean ?: false)
        }
    }
}

/** The style defaults stored next to the other settings, or null for repositories that are not DataStore ones. */
val SettingsRepository.styles: StyleRepository?
    get() = (this as? DataStoreSettingsRepository)?.let(::DataStoreStyleRepository)
