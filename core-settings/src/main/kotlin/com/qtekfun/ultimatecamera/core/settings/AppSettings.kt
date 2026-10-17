package com.qtekfun.ultimatecamera.core.settings

/**
 * The app-wide settings, as read from disk by [SettingsRepository.settings].
 *
 * @property enabledGroups the feature groups shown in the UI; see [FeatureGroup] for the defaults.
 * @property maxBrightness force full screen brightness while the camera is open (REQ-MON-003). Off by default: the
 *   system brightness is used. A slave-camera mode (REQ-NET-008) dims the screen regardless of this value.
 * @property recordingTreeUri the SAF tree URI of the folder chosen for recordings (REQ-STO-001), or null for the default
 *   location. Only stored here: core-storage holds the permission, validation and fallback logic. The URI is specific
 *   to this device; on another phone (restored backup, REQ-BAK-002) its permission is missing and validation fails.
 * @property autoConfigOffered the first-start auto-configuration (D42) was run or skipped, so it is not offered again
 *   at start (it stays available from Settings).
 */
data class AppSettings(
    val enabledGroups: Set<FeatureGroup> = FeatureGroup.defaults,
    val maxBrightness: Boolean = false,
    val recordingTreeUri: String? = null,
    val autoConfigOffered: Boolean = false
) {
    fun isEnabled(group: FeatureGroup): Boolean = group in enabledGroups

    companion object {
        /** Fresh install, after [SettingsRepository.resetToDefaults] or when the file is unreadable. */
        val DEFAULT = AppSettings()
    }
}
