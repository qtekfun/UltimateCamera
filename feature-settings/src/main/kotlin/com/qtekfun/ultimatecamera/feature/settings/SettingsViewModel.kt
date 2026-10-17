package com.qtekfun.ultimatecamera.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.settings.AppSettings
import com.qtekfun.ultimatecamera.core.settings.CaptureModePrefs
import com.qtekfun.ultimatecamera.core.settings.CaptureModeRepository
import com.qtekfun.ultimatecamera.core.settings.FeatureGroup
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import com.qtekfun.ultimatecamera.core.settings.StartMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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

    private val modes = repository as? CaptureModeRepository

    /** Null when the repository does not store modes (test fakes): the row is then hidden. */
    val captureModePrefs: StateFlow<CaptureModePrefs?> = (modes?.captureModePrefs ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setStartMode(mode: StartMode) {
        viewModelScope.launch { modes?.setStartMode(mode) }
    }

    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
