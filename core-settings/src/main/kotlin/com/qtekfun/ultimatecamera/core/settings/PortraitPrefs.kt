package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Settings › Photo › Portrait (background blur) (D73, owner: optional, off by default). Only when it is on does the
 * photo mode probe depth sources and offer the Portrait chip. Kept apart from [SettingsRepository] (detekt limit).
 */
interface PortraitRepository {
    val portraitEnabled: Flow<Boolean>

    suspend fun setPortraitEnabled(enabled: Boolean)
}

/** The portrait key in the settings DataStore; additive and optional (absent = off), so the schema stays 1. */
class DataStorePortraitRepository internal constructor(private val settings: DataStoreSettingsRepository) : PortraitRepository {
    override val portraitEnabled: Flow<Boolean> =
        settings.dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map(::read)
            .distinctUntilChanged()

    override suspend fun setPortraitEnabled(enabled: Boolean) {
        settings.dataStore.edit {
            it[PORTRAIT] = enabled
            it[DataStoreSettingsRepository.SCHEMA] = DataStoreSettingsRepository.SCHEMA_VERSION
        }
    }

    internal companion object {
        val PORTRAIT = booleanPreferencesKey("photo_portrait")

        fun read(preferences: Preferences): Boolean = preferences.asMap()[PORTRAIT] as? Boolean ?: false
    }
}

/** The portrait switch stored next to the other settings, or null for repositories that are not DataStore ones. */
val SettingsRepository.portrait: PortraitRepository?
    get() = (this as? DataStoreSettingsRepository)?.let(::DataStorePortraitRepository)
