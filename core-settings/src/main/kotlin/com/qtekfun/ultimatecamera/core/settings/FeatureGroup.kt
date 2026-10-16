package com.qtekfun.ultimatecamera.core.settings

import androidx.annotation.StringRes

/**
 * A group of features the user can show or hide from Settings (docs/01-arquitectura.md, "Interfaz": one single mode,
 * each feature group is enabled or hidden). A disabled group hides its UI; it never changes what is recorded.
 *
 * Defaults (decision D24 in docs/PROGRESS.md): everything a single-phone video shooter expects is on. [MULTICAMERA]
 * and [REMOTE_MONITOR] (Phase 6) are off: they need a capable phone or a second one and cost battery and heat on
 * modest phones, so they are opt-in.
 *
 * [key] is the persisted name: never rename it, add a migration instead (backup REQ-BAK-002 exports it).
 */
enum class FeatureGroup(val key: String, val defaultEnabled: Boolean, @StringRes val titleRes: Int, @StringRes val descriptionRes: Int) {
    /** Manual ISO, shutter, white balance, focus and AE/AF/AWB locks (REQ-CAM-002). */
    MANUAL_CONTROLS(
        key = "manual_controls",
        defaultEnabled = true,
        titleRes = R.string.settings_group_manual_controls_title,
        descriptionRes = R.string.settings_group_manual_controls_description
    ),

    /** One-tap recording profiles (REQ-CAM-005). */
    PROFILES(
        key = "profiles",
        defaultEnabled = true,
        titleRes = R.string.settings_group_profiles_title,
        descriptionRes = R.string.settings_group_profiles_description
    ),

    /** Peaking, zebras, histogram, waveform, vectorscope, false colour, guides and desqueeze (REQ-MON-001). */
    METERS(
        key = "meters",
        defaultEnabled = true,
        titleRes = R.string.settings_group_meters_title,
        descriptionRes = R.string.settings_group_meters_description
    ),

    /** Log curve and LUT pipeline: import, library, burned-in / monitor-only / dual file (REQ-COL-002..004). */
    LUTS(
        key = "luts",
        defaultEnabled = true,
        titleRes = R.string.settings_group_luts_title,
        descriptionRes = R.string.settings_group_luts_description
    ),

    /** Manual gain, limiter, level meters, channel selection and separate WAV track (REQ-AUD-003, REQ-AUD-004). */
    ADVANCED_AUDIO(
        key = "advanced_audio",
        defaultEnabled = true,
        titleRes = R.string.settings_group_advanced_audio_title,
        descriptionRes = R.string.settings_group_advanced_audio_description
    ),

    /** Concurrent cameras on one phone where the hardware allows it (REQ-NET-001). Off by default. */
    MULTICAMERA(
        key = "multicamera",
        defaultEnabled = false,
        titleRes = R.string.settings_group_multicamera_title,
        descriptionRes = R.string.settings_group_multicamera_description
    ),

    /** Pairing, master/camera/monitor roles and the remote monitor (REQ-NET-002..008). Off by default. */
    REMOTE_MONITOR(
        key = "remote_monitor",
        defaultEnabled = false,
        titleRes = R.string.settings_group_remote_monitor_title,
        descriptionRes = R.string.settings_group_remote_monitor_description
    )
    ;

    companion object {
        /** The groups enabled on a fresh install or after a reset. */
        val defaults: Set<FeatureGroup> = entries.filterTo(mutableSetOf()) { it.defaultEnabled }
    }
}
