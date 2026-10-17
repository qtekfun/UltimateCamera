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
 * @property recordingLook the look baked into recordings (REQ-COL-002/004) as an opaque token, or null for none. Parsed
 *   by the camera feature (`LutChoice`); a LUT that was deleted from the library falls back to none there.
 * @property monitorLook the look shown only on the preview, as an opaque token, or null for automatic.
 * @property volumeKeysRecord the volume keys start and stop recording on the camera screen (D47). On by default.
 * @property alertVibration a short vibration on the first critical health alert (D52). On by default.
 * @property backgroundRecording a take or the multicamera camera role keeps running when the app leaves the screen (D66),
 *   through a foreground service. On by default; off = the take is saved when the app leaves the foreground (D40).
 * @property thermalStepDown "Protect the phone": when the heat forecast predicted SEVERE during a take, the next take
 *   starts one mode lower on the degradation ladder (REQ-NFR-001, D68). Off by default: the step down is only offered.
 * @property recordingLocationMode where takes go (D50): core-storage's `LocationMode` text (`auto`, `phone`,
 *   `volume:<name>`, `folder`), or null when never chosen (a stored [recordingTreeUri] then means `folder`, else `auto`).
 * @property audioInputKey the input device the user picked (REQ-AUD-001), as core-audio's stable key (kind and product
 *   name: Android's device ids change on every reconnection), or null for the automatic choice (USB > Bluetooth > built-in).
 * @property audioGainDb manual input gain (REQ-AUD-004), in dB, [AUDIO_GAIN_MIN_DB]..[AUDIO_GAIN_MAX_DB].
 * @property separateWav also write the take's audio as a separate WAV file next to the MP4 (REQ-AUD-003). Off by default.
 */
data class AppSettings(
    val enabledGroups: Set<FeatureGroup> = FeatureGroup.defaults,
    val maxBrightness: Boolean = false,
    val recordingTreeUri: String? = null,
    val autoConfigOffered: Boolean = false,
    val recordingLook: String? = null,
    val monitorLook: String? = null,
    val volumeKeysRecord: Boolean = true,
    val alertVibration: Boolean = true,
    val backgroundRecording: Boolean = true,
    val thermalStepDown: Boolean = false,
    val recordingLocationMode: String? = null,
    val audioInputKey: String? = null,
    val audioGainDb: Float = 0f,
    val separateWav: Boolean = false
) {
    fun isEnabled(group: FeatureGroup): Boolean = group in enabledGroups

    companion object {
        /** Fresh install, after [SettingsRepository.resetToDefaults] or when the file is unreadable. */
        val DEFAULT = AppSettings()

        const val AUDIO_GAIN_MIN_DB = -20f
        const val AUDIO_GAIN_MAX_DB = 30f
    }
}
