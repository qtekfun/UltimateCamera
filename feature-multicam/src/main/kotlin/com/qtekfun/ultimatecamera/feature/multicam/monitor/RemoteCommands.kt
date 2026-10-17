package com.qtekfun.ultimatecamera.feature.multicam.monitor

import com.qtekfun.ultimatecamera.core.net.control.ChoiceInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody

/** The commands behind the monitor's chips, built from what the camera reports now (pure, unit-tested). */
object RemoteCommands {
    val ISO_STEPS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)

    /** Exposure times in ns: 1/30 … 1/2000 (1/50 and 1/60 for 25/30 fps at a 180° shutter). */
    val SHUTTER_STEPS = listOf(33_333_333L, 20_000_000L, 16_666_667L, 10_000_000L, 8_333_333L, 4_000_000L, 2_000_000L, 1_000_000L, 500_000L)
    val KELVIN_STEPS = listOf(2800, 3200, 4300, 5000, 5600, 6500, 7500)

    /** Focus distances in diopters: infinity, 5 m, 2 m, 1 m, 50 cm, 20 cm. */
    val FOCUS_STEPS = listOf(0f, 0.2f, 0.5f, 1f, 2f, 5f)
    val EV_STEPS = listOf(-2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f)

    private const val DEFAULT_ISO = 100
    private const val DEFAULT_EXPOSURE_NANOS = 20_000_000L

    /** AE-L: freeze the exposure the camera reports now; unlocking returns to auto. */
    fun exposureLock(c: CaptureUi, lock: Boolean): CommandBody.SetExposure =
        if (lock) CommandBody.SetExposure(auto = false, iso = c.iso, exposureTimeNanos = c.exposureNanos) else CommandBody.SetExposure(auto = true)

    fun iso(c: CaptureUi, iso: Int): CommandBody.SetExposure =
        CommandBody.SetExposure(auto = false, iso = iso, exposureTimeNanos = c.exposureNanos ?: DEFAULT_EXPOSURE_NANOS)

    fun shutter(c: CaptureUi, exposureNanos: Long): CommandBody.SetExposure =
        CommandBody.SetExposure(auto = false, iso = c.iso ?: DEFAULT_ISO, exposureTimeNanos = exposureNanos)

    fun ev(ev: Float): CommandBody.SetExposure = CommandBody.SetExposure(auto = true, evCompensation = ev)

    fun focusLock(c: CaptureUi, lock: Boolean): CommandBody.SetFocus =
        if (lock) CommandBody.SetFocus(auto = false, distanceDiopters = c.focusDiopters) else CommandBody.SetFocus(auto = true)

    fun focus(diopters: Float?): CommandBody.SetFocus = if (diopters ==
        null
    ) {
        CommandBody.SetFocus(auto = true)
    } else {
        CommandBody.SetFocus(auto = false, distanceDiopters = diopters)
    }

    fun whiteBalanceLock(c: CaptureUi, lock: Boolean): CommandBody.SetWhiteBalance =
        if (lock) CommandBody.SetWhiteBalance(auto = false, kelvin = c.kelvin) else CommandBody.SetWhiteBalance(auto = true)

    fun whiteBalance(kelvin: Int?): CommandBody.SetWhiteBalance =
        if (kelvin == null) CommandBody.SetWhiteBalance(auto = true) else CommandBody.SetWhiteBalance(auto = false, kelvin = kelvin)

    /** "∞", "2 m", "50 cm" for a focus distance in diopters. */
    fun focusText(diopters: Float): String {
        if (diopters <= 0f) return "∞"
        val meters = 1f / diopters
        return if (meters >= 1f) "%.0f m".format(meters) else "%.0f cm".format(meters * CM_PER_M)
    }

    fun evText(ev: Float): String = if (ev == 0f) "±0" else "%+.1f".format(ev)

    private const val CM_PER_M = 100f
}

/** The profile picker's codec filter (pure, unit-tested). */
object ProfileChoices {
    /** Codecs the camera's profiles use, in first-seen order (the codec picker). */
    fun codecs(profiles: List<ChoiceInfo>): List<String> = profiles.mapNotNull { it.codec }.distinct()

    /** Profiles shown under codec filter [codec] (null: all). */
    fun profilesFor(profiles: List<ChoiceInfo>, codec: String?): List<ChoiceInfo> = if (codec == null) profiles else profiles.filter { it.codec == codec }
}
