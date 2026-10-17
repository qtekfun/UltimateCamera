package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Keeps an Ultra HDR photo's HDR rendition when we change its SDR base (D83). The gain map says, per pixel, how much
 * brighter the HDR picture is than the SDR one: `HDR = (SDR + εsdr)·exp(L) − εhdr`, with
 * `L = mix(ln ratioMin, ln ratioMax, G^(1/γ))` and G the stored 0..1 value (Android `Gainmap`, ISO 21496-1). When the
 * base rendering brightens the SDR picture (white from about 210 to 243, shadows lifted), the old gain would add its
 * full boost on top of the brighter base; rebasing sets `L' = L + ln((SDRold + ε) / (SDRnew + ε))`, clamped to the
 * map's range, so highlights reach the same HDR brightness as before and mid-tones (gain 1) simply follow the new SDR
 * picture. Works on the gain map's own resolution with both pictures scaled down to it; one channel (luminance) or three.
 */
class GainmapRebase(private val ratioMin: FloatArray, private val ratioMax: FloatArray, private val gamma: FloatArray, private val epsilonSdr: FloatArray) {
    /**
     * New gain values (0..255, [channels] per pixel interleaved) for [gain] given the SDR picture [before] and [after]
     * the rendering, both packed RGB at the gain map's size.
     */
    fun rebase(gain: IntArray, channels: Int, before: IntArray, after: IntArray): IntArray {
        require(channels == 1 || channels == 3) { "channels" }
        require(before.size == after.size && gain.size == before.size * channels) { "sizes" }
        val out = IntArray(gain.size)
        val old = FloatArray(3)
        val new = FloatArray(3)
        for (i in before.indices) {
            linear(before[i], old)
            linear(after[i], new)
            for (c in 0 until channels) {
                val k = if (channels == 1) 0 else c
                val sdrOld = if (channels == 1) luminance(old) else old[c]
                val sdrNew = if (channels == 1) luminance(new) else new[c]
                val lo = ln(ratioMin[k].toDouble())
                val hi = ln(ratioMax[k].toDouble())
                val g = gain[i * channels + c]
                if (hi <= lo) {
                    out[i * channels + c] = g
                    continue
                }
                val l = lo + (hi - lo) * (g / MAX).toDouble().pow(1.0 / gamma[k])
                val shifted = (l + ln((sdrOld + epsilonSdr[k] + TINY) / (sdrNew + epsilonSdr[k] + TINY))).coerceIn(lo, hi)
                val encoded = ((shifted - lo) / (hi - lo)).pow(gamma[k].toDouble()) * MAX
                out[i * channels + c] = (encoded + 0.5).toInt().coerceIn(0, MAX.toInt())
            }
        }
        return out
    }

    /**
     * [rebase] on a gain map bitmap's packed pixels: the alpha byte of an ALPHA_8 map ([single]), else R, G, B of an
     * opaque ARGB map. Returns the new packed pixels.
     */
    fun rebasePacked(pixels: IntArray, single: Boolean, before: IntArray, after: IntArray): IntArray {
        if (single) {
            val gain = rebase(IntArray(pixels.size) { pixels[it] ushr 24 }, 1, before, after)
            return IntArray(pixels.size) { gain[it] shl 24 }
        }
        val gain = IntArray(pixels.size * 3)
        for (i in pixels.indices) {
            gain[i * 3] = (pixels[i] shr 16) and 0xFF
            gain[i * 3 + 1] = (pixels[i] shr 8) and 0xFF
            gain[i * 3 + 2] = pixels[i] and 0xFF
        }
        val out = rebase(gain, 3, before, after)
        return IntArray(pixels.size) { OPAQUE or (out[it * 3] shl 16) or (out[it * 3 + 1] shl 8) or out[it * 3 + 2] }
    }

    /** The HDR linear value of one channel: what a display at full headroom shows (for tests). */
    fun hdr(sdrLinear: Float, gain: Int, channel: Int = 0): Double {
        val lo = ln(ratioMin[channel].toDouble())
        val hi = ln(ratioMax[channel].toDouble())
        val l = lo + (hi - lo) * (gain / MAX).toDouble().pow(1.0 / gamma[channel])
        return (sdrLinear + epsilonSdr[channel]) * exp(l)
    }

    private fun linear(rgb: Int, into: FloatArray) {
        into[0] = Srgb.decode(((rgb shr 16) and 0xFF) / MAX)
        into[1] = Srgb.decode(((rgb shr 8) and 0xFF) / MAX)
        into[2] = Srgb.decode((rgb and 0xFF) / MAX)
    }

    private fun luminance(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private companion object {
        const val MAX = 255f
        const val TINY = 1e-4f
        const val OPAQUE = -0x1000000
    }
}
