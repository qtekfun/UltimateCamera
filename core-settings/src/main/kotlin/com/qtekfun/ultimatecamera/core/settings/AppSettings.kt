package com.qtekfun.ultimatecamera.core.settings

/**
 * The app-wide settings, as read from disk by [SettingsRepository.settings].
 *
 * @property enabledGroups the feature groups shown in the UI; see [FeatureGroup] for the defaults.
 * @property maxBrightness force full screen brightness while the camera is open (REQ-MON-003). Off by default: the
 *   system brightness is used. A slave-camera mode (REQ-NET-008) dims the screen regardless of this value.
 */
data class AppSettings(val enabledGroups: Set<FeatureGroup> = FeatureGroup.defaults, val maxBrightness: Boolean = false) {
    fun isEnabled(group: FeatureGroup): Boolean = group in enabledGroups

    companion object {
        /** Fresh install, after [SettingsRepository.resetToDefaults] or when the file is unreadable. */
        val DEFAULT = AppSettings()
    }
}
