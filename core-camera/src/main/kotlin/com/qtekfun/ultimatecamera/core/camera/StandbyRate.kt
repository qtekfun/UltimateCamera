package com.qtekfun.ultimatecamera.core.camera

/**
 * Idle standby (D97): while a camera-role phone waits for REC, a mode above [STANDBY_FPS] runs the camera at
 * [STANDBY_FPS] (same size, same session), which halves the sensor and image-processor work of 4K60; nothing a monitor
 * sees is lost, its stream is capped at 30 fps anyway. REC first brings the full rate back ([atFullRate]).
 */
object StandbyRate {
    const val STANDBY_FPS = 30

    /** A measured rate this close to the mode's counts as back to full rate. */
    const val FULL_RATE_SHARE = 0.9

    /** How often the rate is measured while waiting for it, and for how long at most. */
    const val SAMPLE_MS = 100L
    const val SETTLE_TIMEOUT_MS = 1_500L

    /**
     * The AE target range for standby of a [fps] mode, from the camera's [advertised] ranges: only a listed range
     * ending at [STANDBY_FPS] (fixed first), so the HAL never sees a range it did not offer. Null when the mode is not
     * above [STANDBY_FPS], when nothing fits, or with a vendor operating mode ([vendorSession]), whose sensor modes are
     * not worth the risk.
     */
    fun range(fps: Int, advertised: List<IntRange>, vendorSession: Boolean = false): IntRange? {
        if (fps <= STANDBY_FPS || vendorSession) return null
        return advertised.filter { it.last == STANDBY_FPS && it.first <= STANDBY_FPS }.maxByOrNull { it.first }
    }

    /** True when [measured] fps (null = not measurable yet) is the [fps] of the mode again. */
    fun atFullRate(measured: Double?, fps: Int): Boolean = measured != null && measured >= fps * FULL_RATE_SHARE
}
