package com.qtekfun.ultimatecamera.core.gpu

import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * The illuminant of a whole picture from the surfaces that would be grey (D93). Every pixel's chromaticity in linear
 * light (ln red/green, ln blue/green) goes into a 2D histogram over the casts a camera can plausibly leave ([RANGE]),
 * weighted by its brightness squared, so a white wall weighs more than a dim shadow or a large brown box. The peak of the smoothed histogram is the
 * cast shared by the most bright surfaces: grey and white things (walls, cloth, paper) all share the light's cast,
 * while a coloured object's chromaticity is its own. The estimate is the weighted mean of the pixels near that peak.
 *
 * Why not a power mean of all pixels (Shades of Grey): a lit orange notebook, the brightest thing in the frame, was
 * taken for the light and the grey around it turned cyan. Why not weighting by how grey a pixel already looks: under a
 * strong cast from the camera's own AWB the grey cloth looked blue and nothing counted. A brown box or an orange
 * notebook lies outside [RANGE] (no plausible light makes them grey), a tinted grey lies inside.
 *
 * Clipped pixels (no colour left) and near-black ones (noise) are left out; with too few pixels near the peak there is
 * no estimate. Buffers are allocated once per size; not thread-safe (one per sampler).
 */
class SceneWhite {
    private val histogram = FloatArray(BINS * BINS)
    private var chroma = FloatArray(0)
    private var weights = FloatArray(0)

    /**
     * Reads [count] RGBA8 pixels from [pixels] (from its position 0) and writes the illuminant's red and blue over green
     * into [out] (1 = neutral). False, [out] untouched, when too few pixels share a plausible grey. Allocation-free
     * after the first call with a given [count].
     */
    fun illuminant(pixels: ByteBuffer, count: Int, hlg: Boolean, out: FloatArray): Boolean {
        val total = fill(pixels, count, if (hlg) HLG_LINEAR else SDR_LINEAR)
        if (total <= 0f) return false
        val peak = peak()
        val pr = (peak / BINS + 0.5f) * BIN_SIZE - RANGE
        val pb = (peak % BINS + 0.5f) * BIN_SIZE - RANGE
        var sr = 0.0
        var sb = 0.0
        var near = 0f
        for (i in 0 until count) {
            val w = weights[i]
            val dr = chroma[i * 2] - pr
            val db = chroma[i * 2 + 1] - pb
            if (w <= 0f || dr * dr + db * db > NEAR * NEAR) continue
            sr += w * chroma[i * 2]
            sb += w * chroma[i * 2 + 1]
            near += w
        }
        if (near < total * MIN_PEAK_SHARE) return false
        out[0] = exp(sr / near).toFloat()
        out[1] = exp(sb / near).toFloat()
        return true
    }

    /** Chromaticities and weights of the usable pixels into the histogram; returns their total weight. */
    private fun fill(pixels: ByteBuffer, count: Int, linear: FloatArray): Float {
        if (weights.size < count) {
            chroma = FloatArray(count * 2)
            weights = FloatArray(count)
        }
        histogram.fill(0f)
        var total = 0f
        for (i in 0 until count) total += add(i, pixels, linear)
        return total
    }

    /** Pixel [i] into the histogram, weighted by its brightness squared; 0 when clipped, near black or out of range. */
    private fun add(i: Int, pixels: ByteBuffer, linear: FloatArray): Float {
        weights[i] = 0f
        val r = pixels.get(i * 4).toInt() and 0xFF
        val g = pixels.get(i * 4 + 1).toInt() and 0xFF
        val b = pixels.get(i * 4 + 2).toInt() and 0xFF
        val max = maxOf(r, g, b)
        if (max >= CLIP_CODE || max < DARK_CODE) return 0f
        val cr = ln(linear[r] / linear[g])
        val cb = ln(linear[b] / linear[g])
        val br = bin(cr)
        val bb = bin(cb)
        if (br < 0 || bb < 0) return 0f
        val brightness = (linear[r] + linear[g] + linear[b]) / 3f
        val w = brightness * brightness
        chroma[i * 2] = cr
        chroma[i * 2 + 1] = cb
        weights[i] = w
        histogram[br * BINS + bb] += w
        return w
    }

    /** Histogram bin of a log chromaticity, or -1 outside [RANGE]. */
    private fun bin(c: Float): Int = if (c < -RANGE || c >= RANGE) -1 else ((c + RANGE) / BIN_SIZE).toInt().coerceAtMost(BINS - 1)

    /** The bin whose neighbourhood (Gaussian, [KERNEL] bins around) holds the most weight. */
    private fun peak(): Int {
        var best = 0
        var bestScore = -1f
        for (cell in 0 until BINS * BINS) {
            val score = smoothed(cell / BINS, cell % BINS)
            if (score > bestScore) {
                bestScore = score
                best = cell
            }
        }
        return best
    }

    private fun smoothed(r: Int, b: Int): Float {
        var score = 0f
        for (dr in -KERNEL..KERNEL) {
            for (db in -KERNEL..KERNEL) {
                val rr = r + dr
                val bb = b + db
                if (rr in 0 until BINS && bb in 0 until BINS) score += histogram[rr * BINS + bb] * KERNEL_WEIGHTS[dr * dr + db * db]
            }
        }
        return score
    }

    companion object {
        /** Linear light of each 8-bit code, per input transfer (never 0, so every ratio has a logarithm). */
        private val HLG_LINEAR = FloatArray(256) { UcLog.hlgToLinear(it / 255f).coerceAtLeast(MIN_LINEAR) }
        private val SDR_LINEAR = FloatArray(256) { UcLog.bt709ToLinear(it / 255f).coerceAtLeast(MIN_LINEAR) }

        /**
         * Largest cast considered, in ln of red/green and blue/green (about ×0.4 to ×2.5): the strong blue the OPPO's
         * AWB gave a grey cloth reads about −0.7 in red through the HLG signal (ratios there are squared).
         */
        const val RANGE = 0.9f
        private const val BINS = 30
        private const val BIN_SIZE = 2 * RANGE / BINS
        private const val KERNEL = 2
        private val KERNEL_WEIGHTS = FloatArray(2 * KERNEL * KERNEL + 1) { exp(-it / 2f) }

        /** Pixels within this log-chromaticity distance of the peak make the estimate. */
        private const val NEAR = 0.1f

        /** Least share of the in-range weight near the peak for an estimate. */
        private const val MIN_PEAK_SHARE = 0.05f

        internal const val CLIP_CODE = 250
        internal const val DARK_CODE = 12
        private const val MIN_LINEAR = 1e-4f
    }
}
