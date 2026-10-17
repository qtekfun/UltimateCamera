package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow

/**
 * The camera pair chosen for the experimental second camera (D62): the two camera ids sorted and joined by a comma
 * (`0,1`), or null for automatic (a front camera first, as in D59). Implemented by [DataStoreSettingsRepository]; kept
 * apart from [SettingsRepository] so fakes need not change.
 */
interface SecondCameraRepository {
    val secondCameraPair: Flow<String?>

    suspend fun setSecondCameraPair(pair: String?)
}

/** Additive optional key: its absence means automatic, so the schema version does not change. */
internal object SecondCameraKeys {
    val PAIR = stringPreferencesKey("second_camera_pair")

    fun read(preferences: Preferences): String? = (preferences.asMap()[PAIR] as? String)?.takeIf { it.isNotBlank() }

    fun write(preferences: MutablePreferences, pair: String?) {
        if (pair == null) preferences.remove(PAIR) else preferences[PAIR] = pair
    }
}
