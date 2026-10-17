package com.qtekfun.ultimatecamera.core.camera

/** White balance: the camera's automatic mode, a preset, or a manual colour temperature ([CameraControls.kelvin]). */
enum class WhiteBalance { AUTO, DAYLIGHT, CLOUDY, TUNGSTEN, FLUORESCENT, MANUAL }

/** A point of the recorded picture, 0..1 from its left/top edge (sensor orientation, as the encoder receives it). */
data class ImagePoint(val x: Float, val y: Float)

/**
 * What the user asked the camera to do (REQ-CAM-002). Everything here is a per-frame control, so a change never
 * reconfigures the session: it only replaces the repeating request. Values are requests; [ControlRanges.clamp] fits them
 * to what the camera can do before they are sent.
 */
data class CameraControls(
    /** Manual exposure: [iso] and [exposureTimeNs] are used and auto exposure is off. */
    val manualExposure: Boolean = false,
    val iso: Int = DEFAULT_ISO,
    val exposureTimeNs: Long = DEFAULT_EXPOSURE_NS,
    /** Exposure compensation in the camera's steps ([ControlRanges.evStep] EV each); auto exposure only. */
    val exposureCompensation: Int = 0,
    val aeLock: Boolean = false,
    /** Manual focus at [focusDiopters] (0 = infinity); continuous video autofocus otherwise. */
    val manualFocus: Boolean = false,
    val focusDiopters: Float = 0f,
    val afLock: Boolean = false,
    /** Where to focus and meter (tap on the preview); null = the whole picture, as the camera decides. */
    val meteringPoint: ImagePoint? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val kelvin: Int = DEFAULT_KELVIN,
    val awbLock: Boolean = false
) {
    companion object {
        const val DEFAULT_ISO = 100
        const val DEFAULT_EXPOSURE_NS = 20_000_000L
        const val DEFAULT_KELVIN = 5600
    }
}

/** What the camera is actually doing, from the capture results (shown even in auto). Null = not reported. */
data class LiveExposure(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDiopters: Float? = null,
    val aeLocked: Boolean = false,
    val afLocked: Boolean = false
)
