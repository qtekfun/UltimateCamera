package com.qtekfun.ultimatecamera.core.gpu

import kotlin.math.exp
import kotlin.math.ln

/**
 * The per-channel correction the renderer applies to the camera signal for the scene white balance (D93): it removes
 * the cast [SceneWhite] measured on the camera's picture, tint included, on every phone (the camera's own gains, where
 * they can be set, only move along the colour-temperature line and are not offered by every camera).
 *
 * Gains are in linear light, the smallest at 1 so a clipped white stays white (as raw white-balance gains), at most
 * [MAX_GAIN]; they move [SMOOTHING] of the way per measurement in log space, so the picture never jumps. The shader
 * multiplies the signal, so [signalGains] raises them to the transfer's exponent: exact for the lower half of HLG
 * (`E = √(3L)`) and the power part of BT.709, close enough for the small corrections above it.
 */
class SceneTint(hlg: Boolean) {
    private val exponent = if (hlg) HLG_EXPONENT else SDR_EXPONENT
    private val logGain = DoubleArray(3)
    private var started = false

    /** Gains for the signal (red, green, blue), 1 = no change. Read on the GL thread after [update]. */
    val signalGains = floatArrayOf(1f, 1f, 1f)

    /** Takes one measurement (illuminant red and blue over green, 1 = neutral). */
    fun update(red: Float, blue: Float) {
        if (red <= 0f || blue <= 0f) return
        val target = doubleArrayOf(-ln(red.toDouble()), 0.0, -ln(blue.toDouble()))
        val floor = target.min()
        val cap = ln(MAX_GAIN)
        for (i in 0 until 3) {
            val t = (target[i] - floor).coerceAtMost(cap)
            logGain[i] = if (started) logGain[i] + SMOOTHING * (t - logGain[i]) else t
            signalGains[i] = exp(logGain[i] * exponent).toFloat()
        }
        started = true
    }

    /** Back to no correction (another white balance chosen). */
    fun reset() {
        started = false
        logGain.fill(0.0)
        signalGains.fill(1f)
    }

    /** Linear gain of channel [i] (for tests and readouts). */
    fun linearGain(i: Int): Float = exp(logGain[i]).toFloat()

    companion object {
        const val SMOOTHING = 0.35
        const val MAX_GAIN = 2.0
        const val HLG_EXPONENT = 0.5
        const val SDR_EXPONENT = 0.45
    }
}

/** The GPU scene white balance: off, following the scene, or holding its last correction (AWB lock). */
enum class SceneTintMode { OFF, LIVE, HOLD }
