package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.Flow

/**
 * HDR display and experimental vendor options (D37, D63).
 *
 * @property hdrViewfinder show a 10-bit HLG take's preview as HDR on an HDR display (Settings › Display). On by
 *   default; it has no effect where the display or the GPU driver cannot show HDR.
 * @property experimental the "Experimental" switch of D37: vendor features (sensor HDR) are offered only when it is on.
 *   Off by default.
 * @property sensorHdr the camera screen's "HDR (sensor)" toggle; only applied while [experimental] is on and the
 *   auto-configuration measured the mode to hold.
 */
data class HdrPrefs(val hdrViewfinder: Boolean = true, val experimental: Boolean = false, val sensorHdr: Boolean = false)

/** Implemented by [DataStoreSettingsRepository]; kept apart from [SettingsRepository] so fakes need not change. */
interface HdrRepository {
    val hdrPrefs: Flow<HdrPrefs>

    suspend fun setHdrPrefs(prefs: HdrPrefs)
}

/** Additive optional keys: their absence means the defaults, so the schema version does not change. */
internal object HdrKeys {
    val HDR_VIEWFINDER = booleanPreferencesKey("hdr_viewfinder")
    val EXPERIMENTAL = booleanPreferencesKey("experimental_vendor")
    val SENSOR_HDR = booleanPreferencesKey("sensor_hdr")

    fun read(preferences: Preferences): HdrPrefs = HdrPrefs(
        hdrViewfinder = preferences.asMap()[HDR_VIEWFINDER] as? Boolean ?: true,
        experimental = preferences.asMap()[EXPERIMENTAL] as? Boolean ?: false,
        sensorHdr = preferences.asMap()[SENSOR_HDR] as? Boolean ?: false
    )

    fun write(preferences: MutablePreferences, prefs: HdrPrefs) {
        preferences[HDR_VIEWFINDER] = prefs.hdrViewfinder
        preferences[EXPERIMENTAL] = prefs.experimental
        preferences[SENSOR_HDR] = prefs.sensorHdr
    }
}
