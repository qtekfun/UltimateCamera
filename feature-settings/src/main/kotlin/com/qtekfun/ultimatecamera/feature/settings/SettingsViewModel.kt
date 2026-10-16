package com.qtekfun.ultimatecamera.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.settings.AppSettings
import com.qtekfun.ultimatecamera.core.settings.FeatureGroup
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings screen state: the persisted [AppSettings] (null until the first read from disk) and their setters. */
class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setFeatureGroupEnabled(group: FeatureGroup, enabled: Boolean) {
        viewModelScope.launch { repository.setFeatureGroupEnabled(group, enabled) }
    }

    fun setMaxBrightness(enabled: Boolean) {
        viewModelScope.launch { repository.setMaxBrightness(enabled) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
