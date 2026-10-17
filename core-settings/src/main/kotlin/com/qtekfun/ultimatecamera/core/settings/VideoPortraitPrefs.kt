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
 * Settings › Recording › Background blur in video (D89, REQ-PHO-005; optional, off by default like the photo's D73).
 * Only when it is on does the video mode probe depth and offer the blur chip.
 */
interface VideoPortraitRepository {
    val videoPortraitEnabled: Flow<Boolean>

    suspend fun setVideoPortraitEnabled(enabled: Boolean)
}

/** The key in the settings DataStore; additive and optional (absent = off), so the schema stays 1. */
class DataStoreVideoPortraitRepository internal constructor(private val settings: DataStoreSettingsRepository) : VideoPortraitRepository {
    override val videoPortraitEnabled: Flow<Boolean> =
        settings.dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map(::read)
            .distinctUntilChanged()

    override suspend fun setVideoPortraitEnabled(enabled: Boolean) {
        settings.dataStore.edit {
            it[VIDEO_PORTRAIT] = enabled
            it[DataStoreSettingsRepository.SCHEMA] = DataStoreSettingsRepository.SCHEMA_VERSION
        }
    }

    internal companion object {
        val VIDEO_PORTRAIT = booleanPreferencesKey("video_portrait")

        fun read(preferences: Preferences): Boolean = preferences.asMap()[VIDEO_PORTRAIT] as? Boolean ?: false
    }
}

/** The video blur switch stored next to the other settings, or null for repositories that are not DataStore ones. */
val SettingsRepository.videoPortrait: VideoPortraitRepository?
    get() = (this as? DataStoreSettingsRepository)?.let(::DataStoreVideoPortraitRepository)
