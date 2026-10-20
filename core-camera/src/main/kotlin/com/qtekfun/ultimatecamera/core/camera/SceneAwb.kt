package com.qtekfun.ultimatecamera.core.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Scene white balance for AUTO: the camera's own AWB follows the dominant colour (a brown box filling the frame turned
 * the white wall behind it blue), so the app measures the cast left in the picture over the whole scene (bright,
 * unclipped light weighs most) and keeps the camera in manual gains on the Planckian locus that remove it.
 *
 * Closed loop: each measurement is the illuminant still visible in the picture taken with [current] gains; the gains
 * that would neutralise it are projected onto the locus (a colour temperature, so a single odd scene cannot go green or
 * magenta) and the temperature moves part of the way there in mireds, with a small dead band so it never hunts.
 */
class SceneAwb(private val kelvinRange: IntRange, gainsAt: (Int) -> RggbGains) {
    private val kelvins = (kelvinRange.first..kelvinRange.last step STEP_K).toList()
    private val logGains = kelvins.map { k -> gainsAt(k).let { ln(it.red.toDouble()) to ln(it.blue.toDouble()) } }

    /** The temperature in use, null until the first measurement. */
    var kelvin: Int? = null
        private set

    /** Forgets the temperature (the next measurement starts again from the camera's gains). */
    fun reset() {
        kelvin = null
    }

    /**
     * Takes one measurement: [red] and [blue] are the picture's illuminant over green (1 = neutral) under [current]
     * gains. Returns the new temperature when it changed, null when it stays.
     */
    fun update(current: RggbGains, red: Float, blue: Float): Int? {
        if (minOf(red, blue, current.red, current.blue) <= 0f) return null
        val target = nearestKelvin(ln(current.red.toDouble() / red), ln(current.blue.toDouble() / blue))
        val previous = kelvin
        val next = if (previous == null) {
            target
        } else {
            val mired = mired(previous) + DAMPING * (mired(target) - mired(previous))
            if (abs(mired - mired(previous)) < DEAD_BAND_MIRED) return null
            (MIRED_SCALE / mired).roundToInt().coerceIn(kelvinRange)
        }
        if (next == previous) return null
        kelvin = next
        return next
    }

    /** The locus temperature whose gains are closest to (ln red gain, ln blue gain). */
    internal fun nearestKelvin(logRed: Double, logBlue: Double): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        logGains.forEachIndexed { i, (r, b) ->
            val d = (r - logRed) * (r - logRed) + (b - logBlue) * (b - logBlue)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return kelvins[best]
    }

    private fun mired(kelvin: Int): Double = MIRED_SCALE / kelvin

    companion object {
        /** The temperature white balance is sent as gains for: manual Kelvin, or AUTO's scene temperature; null = AWB mode. */
        fun gainsKelvin(c: CameraControls, sceneKelvin: Int?): Int? = when (c.whiteBalance) {
            WhiteBalance.MANUAL -> c.kelvin
            WhiteBalance.AUTO -> sceneKelvin
            else -> null
        }

        const val STEP_K = 50

        /** Share of the way to the measured temperature taken per measurement (a few per second). */
        const val DAMPING = 0.35

        /** Changes below this are not sent (about 50 K at 5000 K). */
        const val DEAD_BAND_MIRED = 2.0
        private const val MIRED_SCALE = 1_000_000.0
    }
}
