package com.qtekfun.ultimatecamera.core.camera

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Scene white balance for AUTO (D93): the camera's own AWB follows the dominant colour (a brown box filling the frame
 * turned the white wall behind it blue), so the app measures the cast left in the picture over the whole scene (bright,
 * unclipped light weighs most) and keeps the camera in manual gains that remove it.
 *
 * Closed loop on the red and blue gains directly (green = 1), not on a colour temperature: the Kelvin → gains
 * conversion from the calibration matrices missed the OPPO CPH2841's own neutral by 5–10 % in red, so a temperature
 * pinned the picture cyan with no way back. Each measurement is the illuminant still visible under the current gains;
 * the gains move [DAMPING] of the way to removing it (log space), with a small dead band so they never hunt.
 *
 * They stay within [bounds], what the sensor can need under real light: from its calibration, the gains between 2000 K
 * and 10000 K widened by [TINT_MARGIN] for tint and calibration error ([locusBounds]). Bounds around the camera's own
 * gains held the picture to the camera's mistake: on the OPPO its AWB started at red 1.50 with an orange notebook in
 * view (2.43 on the plain wall), and red could not reach the 2.23+ the grey cloth needed. Without a calibration the
 * camera's gains (the anchor) are the only reference, ±[ANCHOR_DEVIATION].
 */
class SceneAwb(private val bounds: GainBounds? = null) {
    /** The gains in use, null until the first measurement. */
    var gains: RggbGains? = null
        private set

    /** Forgets the gains (the next measurement starts again from the camera's). */
    fun reset() {
        gains = null
    }

    /**
     * Takes one measurement: [red] and [blue] are the picture's illuminant over green (1 = neutral) under [current]
     * gains; [anchor] are the camera's own automatic gains. Returns the new gains when they changed, null when not.
     */
    fun update(current: RggbGains, anchor: RggbGains, red: Float, blue: Float): RggbGains? {
        if (minOf(red, blue, minOf(current.red, current.blue, anchor.red, anchor.blue)) <= 0f) return null
        val from = gains ?: current
        val r = ln(from.red.toDouble())
        val b = ln(from.blue.toDouble())
        val limits = bounds ?: GainBounds.around(anchor, ANCHOR_DEVIATION)
        val toRed = ln((current.red / red).toDouble()).coerceIn(limits.logRed) - r
        val toBlue = ln((current.blue / blue).toDouble()).coerceIn(limits.logBlue) - b
        if (gains != null && abs(toRed) < DEAD_BAND && abs(toBlue) < DEAD_BAND) return null
        return RggbGains(exp(r + DAMPING * toRed).toFloat(), 1f, 1f, exp(b + DAMPING * toBlue).toFloat()).also { gains = it }
    }

    companion object {
        /**
         * The gains sent with AWB off, or null for an AWB mode: manual Kelvin through the calibrations, or AUTO's
         * [sceneGains]; both need the camera's automatic colour matrix ([hasAutoTransform]).
         */
        fun manualGains(
            c: CameraControls,
            hasAutoTransform: Boolean,
            calibrations: Pair<ColorCalibration, ColorCalibration?>?,
            sceneGains: RggbGains?
        ): RggbGains? = when {
            !hasAutoTransform -> null
            c.whiteBalance == WhiteBalance.MANUAL -> calibrations?.let { KelvinMath.kelvinToGains(c.kelvin, it.first, it.second) }
            c.whiteBalance == WhiteBalance.AUTO -> sceneGains
            else -> null
        }

        /** Share of the way to the measured gains taken per measurement (a few per second). */
        const val DAMPING = 0.35

        /** Gains this close to the measured ones (log gain, about 0.5 %) are not changed. */
        const val DEAD_BAND = 0.005

        /** Room around the calibrated 2000–10000 K gains (log gain, about ×0.78 to ×1.28). */
        const val TINT_MARGIN = 0.25

        /** Room around the camera's own gains when there is no calibration (log gain, about ×0.5 to ×2). */
        const val ANCHOR_DEVIATION = 0.7

        /** The gains a calibrated sensor can need: its 2000–10000 K gains, widened by [TINT_MARGIN]. */
        fun locusBounds(first: ColorCalibration, second: ColorCalibration?): GainBounds {
            val gains = (LOCUS_FROM_K..LOCUS_TO_K step LOCUS_STEP_K).map { KelvinMath.kelvinToGains(it, first, second) }
            val red = gains.map { ln(it.red.toDouble()) }
            val blue = gains.map { ln(it.blue.toDouble()) }
            return GainBounds(
                red.min() - TINT_MARGIN..red.max() + TINT_MARGIN,
                blue.min() - TINT_MARGIN..blue.max() + TINT_MARGIN
            )
        }

        private const val LOCUS_FROM_K = 2000
        private const val LOCUS_TO_K = 10000
        private const val LOCUS_STEP_K = 250
    }
}

/** Allowed white-balance gains, as ln of the red and blue gains (green = 1). */
data class GainBounds(val logRed: ClosedFloatingPointRange<Double>, val logBlue: ClosedFloatingPointRange<Double>) {
    companion object {
        /** [deviation] (log gain) either side of [gains]. */
        fun around(gains: RggbGains, deviation: Double): GainBounds {
            val r = ln(gains.red.toDouble())
            val b = ln(gains.blue.toDouble())
            return GainBounds(r - deviation..r + deviation, b - deviation..b + deviation)
        }
    }
}
