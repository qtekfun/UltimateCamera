package com.qtekfun.ultimatecamera.core.gpu

import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * The illuminant of a whole picture, Shades of Grey style (Finlayson & Trezzi 2004): per channel the [POWER]-mean of the
 * scene-linear light, so bright surfaces (a white wall, paper, a grey card) decide and a large coloured object in the
 * middle (a brown box) does not, as it does with plain grey world. Clipped pixels (no colour left) and near-black ones
 * (noise) are left out.
 */
object SceneWhite {
    /** Linear light of each 8-bit code, per input transfer. */
    private val hlgLinear = FloatArray(LEVELS) { UcLog.hlgToLinear(it / MAX_CODE) }
    private val sdrLinear = FloatArray(LEVELS) { UcLog.bt709ToLinear(it / MAX_CODE) }

    /**
     * Reads [count] RGBA8 pixels from [pixels] (from its position 0) and writes the illuminant's red and blue over green
     * into [out] (1 = neutral). False, [out] untouched, when too few pixels are usable. Allocation-free.
     */
    fun illuminant(pixels: ByteBuffer, count: Int, hlg: Boolean, out: FloatArray): Boolean {
        val linear = if (hlg) hlgLinear else sdrLinear
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var used = 0
        for (i in 0 until count) {
            val r = pixels.get(i * 4).toInt() and 0xFF
            val g = pixels.get(i * 4 + 1).toInt() and 0xFF
            val b = pixels.get(i * 4 + 2).toInt() and 0xFF
            val max = maxOf(r, g, b)
            if (max >= CLIP_CODE || max < DARK_CODE) continue
            sr += linear[r].toDouble().pow(POWER)
            sg += linear[g].toDouble().pow(POWER)
            sb += linear[b].toDouble().pow(POWER)
            used++
        }
        if (used < count * MIN_USED_SHARE || sg <= 0.0) return false
        out[0] = (sr / sg).pow(1.0 / POWER).toFloat()
        out[1] = (sb / sg).pow(1.0 / POWER).toFloat()
        return true
    }

    /** Minkowski power: 1 is grey world, ∞ is white patch; 4 lets the brightest areas lead without one pixel deciding. */
    const val POWER = 4.0
    private const val LEVELS = 256
    private const val MAX_CODE = 255f
    internal const val CLIP_CODE = 250
    internal const val DARK_CODE = 12
    private const val MIN_USED_SHARE = 0.05
}
