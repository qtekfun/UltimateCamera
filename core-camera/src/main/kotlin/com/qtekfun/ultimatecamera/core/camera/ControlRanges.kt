package com.qtekfun.ultimatecamera.core.camera

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A rectangle on the sensor's active array, in pixels (left/top inclusive, width/height > 0). */
data class SensorRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * What one camera lets the user control, read from its characteristics (pure data, so the maths is testable on the JVM).
 * Null ranges mean "not controllable": manual exposure needs `MANUAL_SENSOR`, Kelvin needs `MANUAL_POST_PROCESSING`
 * plus colour calibration, manual focus a focuser ([minFocusDiopters] > 0).
 */
data class ControlRanges(
    val isoRange: IntRange? = null,
    val exposureTimeRangeNs: LongRange? = null,
    val evRange: IntRange = 0..0,
    /** EV per compensation step (usually 1/3 or 1/2). */
    val evStep: Float = 1f / 3,
    /** Nearest focus distance in diopters; 0 = fixed focus. */
    val minFocusDiopters: Float = 0f,
    val manualFocus: Boolean = false,
    val whiteBalances: Set<WhiteBalance> = setOf(WhiteBalance.AUTO),
    val kelvinRange: IntRange? = null,
    /** White-balance gains can be set with AWB off (the scene white balance for AUTO, D93). */
    val manualWhiteGains: Boolean = false,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
    val maxAeRegions: Int = 0,
    val maxAfRegions: Int = 0,
    val activeArray: SensorRect = SensorRect(0, 0, 1, 1),
    /** `CONTROL_ZOOM_RATIO_RANGE` (1..1 = no zoom). */
    val zoomRange: ClosedFloatingPointRange<Float> = 1f..1f,
    /** The lens offers optical stabilisation (`LENS_OPTICAL_STABILIZATION_MODE_ON`). */
    val ois: Boolean = false,
    /** Electronic stabilisation modes offered (always contains OFF). */
    val stabilizations: Set<VideoStabilization> = setOf(VideoStabilization.OFF)
) {
    val zoomable: Boolean get() = zoomRange.endInclusive > zoomRange.start

    val manualExposure: Boolean get() = isoRange != null && exposureTimeRangeNs != null

    /**
     * Fits [controls] to this camera at [fps]: unsupported modes fall back to auto, values clamp to their ranges and the
     * exposure time never exceeds the frame time (a longer one would drop the frame rate).
     */
    fun clamp(controls: CameraControls, fps: Int): CameraControls {
        val iso = isoRange
        val shutter = exposureTimeRangeNs?.let { maxExposureNs(it, fps) }
        val kelvins = kelvinRange
        val wb = when {
            controls.whiteBalance == WhiteBalance.MANUAL && kelvins == null -> WhiteBalance.AUTO
            controls.whiteBalance in whiteBalances || controls.whiteBalance == WhiteBalance.MANUAL -> controls.whiteBalance
            else -> WhiteBalance.AUTO
        }
        return controls.copy(
            manualExposure = controls.manualExposure && manualExposure,
            iso = iso?.let { controls.iso.coerceIn(it) } ?: controls.iso,
            exposureTimeNs = shutter?.let { controls.exposureTimeNs.coerceIn(it) } ?: controls.exposureTimeNs,
            exposureCompensation = controls.exposureCompensation.coerceIn(evRange),
            aeLock = controls.aeLock && aeLock,
            manualFocus = controls.manualFocus && manualFocus,
            focusDiopters = controls.focusDiopters.coerceIn(0f, minFocusDiopters),
            meteringPoint = controls.meteringPoint?.let { ImagePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) },
            whiteBalance = wb,
            kelvin = kelvins?.let { controls.kelvin.coerceIn(it) } ?: controls.kelvin,
            awbLock = controls.awbLock && awbLock,
            zoomRatio = controls.zoomRatio.coerceIn(zoomRange)
        )
    }

    /** Exposure-time range usable at [fps]: capped at one frame time. */
    fun maxExposureNs(range: LongRange, fps: Int): LongRange {
        val frame = ControlMath.frameDurationNs(fps)
        val upper = minOf(range.last, frame)
        return range.first..maxOf(range.first, upper)
    }

    /** ISO stops (1/3 stop, the usual camera scale) inside [isoRange], with its ends. */
    fun isoStops(): List<Int> {
        val range = isoRange ?: return emptyList()
        return (ISO_SCALE.filter { it in range } + range.first + range.last).distinct().sorted()
    }

    /** Shutter speeds offered at [fps]: the 1/3-stop scale, the 180° shutter, and the range ends, longest last. */
    fun shutterStops(fps: Int): List<Long> {
        val range = exposureTimeRangeNs?.let { maxExposureNs(it, fps) } ?: return emptyList()
        val scale = SHUTTER_DENOMINATORS.map { (NANOS_PER_SECOND / it).roundToLong() }
        val candidates = scale + ControlMath.shutter180Ns(fps) + range.first + range.last
        return candidates.filter { it in range }.distinct().sorted()
    }

    /** EV value of compensation [steps]. */
    fun ev(steps: Int): Float = steps * evStep

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        /** Third-stop ISO scale. */
        val ISO_SCALE = listOf(
            25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000,
            5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200
        )

        /** Third-stop shutter scale as 1/x seconds, plus the video speeds 1/24, 1/48 and 1/120. */
        val SHUTTER_DENOMINATORS = listOf(
            8000.0, 6400.0, 5000.0, 4000.0, 3200.0, 2500.0, 2000.0, 1600.0, 1250.0, 1000.0, 800.0, 640.0, 500.0, 400.0, 320.0,
            250.0, 200.0, 160.0, 125.0, 120.0, 100.0, 80.0, 60.0, 50.0, 48.0, 40.0, 30.0, 25.0, 24.0, 20.0, 15.0, 13.0, 10.0,
            8.0, 6.0, 5.0, 4.0, 3.0, 2.0
        )

        /** The stop of [stops] nearest to [value] (for steppers starting from a live auto value). */
        fun <T : Number> nearestIndex(stops: List<T>, value: Double): Int = stops.indices.minByOrNull { abs(stops[it].toDouble() - value) } ?: 0

        /** Shutter as photographers write it: `1/50`, or seconds from 1/2 s up (`0.5s`). */
        fun formatShutter(ns: Long): String {
            val seconds = ns / NANOS_PER_SECOND
            return if (seconds >= 0.5) "%.1fs".format(seconds) else "1/${(1 / seconds).roundToInt()}"
        }

        /** Focus distance in metres (∞ at 0 diopters). */
        fun formatFocus(diopters: Float): String = if (diopters <= 0f) "∞" else "%.2f m".format(1f / diopters)

        /** Exposure compensation as `+0.7`, `-1.0`, `0.0`. */
        fun formatEv(ev: Float): String = "%+.1f".format(ev).replace("+0.0", "0.0").replace("-0.0", "0.0")
    }
}
