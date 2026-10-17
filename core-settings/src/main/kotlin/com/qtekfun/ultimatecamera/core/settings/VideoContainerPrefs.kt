package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Settings › Recording › Container (D69): MP4 (default) or a QuickTime movie. [key] is persisted. */
enum class VideoContainerChoice(val key: String) {
    MP4("mp4"),
    MOV("mov")
}

/** The container setting. Kept apart from [SettingsRepository] (at detekt's function limit) and its fakes. */
interface VideoContainerRepository {
    val videoContainer: Flow<VideoContainerChoice>

    suspend fun setVideoContainer(choice: VideoContainerChoice)
}

/** The container key in the settings DataStore; additive and optional (absent = MP4), so the schema stays 1. */
class DataStoreVideoContainerRepository internal constructor(private val settings: DataStoreSettingsRepository) : VideoContainerRepository {
    override val videoContainer: Flow<VideoContainerChoice> =
        settings.dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map(::read)
            .distinctUntilChanged()

    override suspend fun setVideoContainer(choice: VideoContainerChoice) {
        settings.dataStore.edit {
            it[CONTAINER] = choice.key
            it[DataStoreSettingsRepository.SCHEMA] = DataStoreSettingsRepository.SCHEMA_VERSION
        }
    }

    internal companion object {
        val CONTAINER = stringPreferencesKey("video_container")

        fun read(preferences: Preferences): VideoContainerChoice =
            VideoContainerChoice.entries.firstOrNull { it.key == (preferences.asMap()[CONTAINER] as? String) } ?: VideoContainerChoice.MP4
    }
}

/** The container setting stored next to the other settings, or null for repositories that are not DataStore ones. */
val SettingsRepository.videoContainer: VideoContainerRepository?
    get() = (this as? DataStoreSettingsRepository)?.let(::DataStoreVideoContainerRepository)
