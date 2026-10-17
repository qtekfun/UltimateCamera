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

/** The photo mode's default HDR (D64): the best method the camera offers, or none. [key] is persisted. */
enum class PhotoHdrDefault(val key: String) {
    AUTO("auto"),
    OFF("off")
}

/** Settings › Photo › HDR. Kept apart from [SettingsRepository] (at detekt's function limit) and its fakes. */
interface PhotoHdrRepository {
    val photoHdrDefault: Flow<PhotoHdrDefault>

    suspend fun setPhotoHdrDefault(mode: PhotoHdrDefault)
}

/** The photo HDR key in the settings DataStore; additive and optional (absent = auto), so the schema stays 1. */
class DataStorePhotoHdrRepository internal constructor(private val settings: DataStoreSettingsRepository) : PhotoHdrRepository {
    override val photoHdrDefault: Flow<PhotoHdrDefault> =
        settings.dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map(::read)
            .distinctUntilChanged()

    override suspend fun setPhotoHdrDefault(mode: PhotoHdrDefault) {
        settings.dataStore.edit {
            it[PHOTO_HDR] = mode.key
            it[DataStoreSettingsRepository.SCHEMA] = DataStoreSettingsRepository.SCHEMA_VERSION
        }
    }

    internal companion object {
        val PHOTO_HDR = stringPreferencesKey("photo_hdr_default")

        fun read(preferences: Preferences): PhotoHdrDefault =
            PhotoHdrDefault.entries.firstOrNull { it.key == (preferences.asMap()[PHOTO_HDR] as? String) } ?: PhotoHdrDefault.AUTO
    }
}

/** The photo HDR preference stored next to the other settings, or null for repositories that are not DataStore ones. */
val SettingsRepository.photoHdr: PhotoHdrRepository?
    get() = (this as? DataStoreSettingsRepository)?.let(::DataStorePhotoHdrRepository)
