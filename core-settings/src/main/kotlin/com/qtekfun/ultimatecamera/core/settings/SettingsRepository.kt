package com.qtekfun.ultimatecamera.core.settings

import kotlinx.coroutines.flow.Flow

/** Persisted app settings. Every write goes to disk before the call returns. */
interface SettingsRepository {
    /** The current settings, then every change. Never fails: unreadable data yields [AppSettings.DEFAULT]. */
    val settings: Flow<AppSettings>

    suspend fun setFeatureGroupEnabled(group: FeatureGroup, enabled: Boolean)

    suspend fun setMaxBrightness(enabled: Boolean)

    /** Drops every stored value; [settings] then emits [AppSettings.DEFAULT]. */
    suspend fun resetToDefaults()
}
