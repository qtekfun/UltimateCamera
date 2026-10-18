package com.qtekfun.ultimatecamera.core.gpu.hdr

import kotlin.math.pow

/**
 * The tone of the base rendering (D84), on luma in log2 linear light (L), applied to colour as a ratio:
 * - **white**: [whiteGain] stops so the scene's white (the input's [RenderParams.whitePercentile]) lands on
 *   [RenderParams.whiteTarget] after the whole curve. A camera's JPEG_R SDR base leaves headroom for its gain map (the
 *   owner's OPPO: white wall at 205–215 where its own camera puts 240); the SDR picture has to stand on its own.
 * - **shadows**: the edge-aware base is lifted by [RenderParams.shadowStrength] × its distance below
 *   [RenderParams.shadowPivot] (at most [RenderParams.maxShadowLift] stops), faded out over the two stops above the
 *   input's own black (or [RenderParams.shadowFloor]), so a dark corridor opens up while black objects and noise stay
 *   down;
 * - **detail**: `L − base` (local contrast) is kept and boosted by [RenderParams.detail], faded out the same way;
 * - **shoulder**: linear light above [RenderParams.shoulderKnee] rolls off so that the brightest input (1 × the white
 *   gain) lands exactly on 1, never clipping a gradient;
 * - then, per channel ([finish]): the black point subtracted in linear light (it only bites near black, so shadows
 *   keep their level and colour) and the S curve of D74.
 */
class ToneMap(private val params: RenderParams, blackLuma: Float, whiteLuma: Float) {
    /** Stops of exposure added to reach the white target, within 0..[RenderParams.maxWhiteGain]. */
    val whiteGain: Float

    /** Linear light subtracted (then rescaled to keep 1) so the input's darkest pixels land on [RenderParams.blackTarget]. */
    val blackPoint: Float

    /** Log luma below which lift and detail fade out: the input's own black, or [RenderParams.shadowFloor]. */
    val floor: Float

    private val table = FloatArray(OUT_SIZE + 1)
    private val finishTable = FloatArray(FINISH_SIZE + 1)

    init {
        val lw = LogLuma.of(whiteLuma)
        val lb = LogLuma.of(blackLuma)
        floor = maxOf(params.shadowFloor, lb)
        var gain = 0f
        var black = 0f
        repeat(2) {
            gain = solveGain(lw, black)
            black = solveBlack(lb, gain)
        }
        whiteGain = gain
        blackPoint = black
        for (k in 0..OUT_SIZE) table[k] = Srgb.encode(shoulder(2f.pow(OUT_MIN + k * OUT_STEP), gain))
        for (k in 0..FINISH_SIZE) finishTable[k] = finish(k.toFloat() / FINISH_SIZE, black)
    }

    private fun solveGain(lw: Float, black: Float): Float {
        val target = params.whiteTarget
        val max = params.maxWhiteGain
        return when {
            target <= 0f || display(lw, 0f, black) >= target -> 0f
            display(lw + max, max, black) <= target -> max
            else -> {
                var lo = 0f
                var hi = max
                repeat(BISECTIONS) {
                    val mid = (lo + hi) / 2
                    if (display(lw + mid, mid, black) < target) lo = mid else hi = mid
                }
                (lo + hi) / 2
            }
        }
    }

    /** The black point that brings the input's dark end [lb] (after the gain) to the black target; never lifts. */
    private fun solveBlack(lb: Float, gain: Float): Float {
        if (params.blackTarget <= 0f) return 0f
        val v = shoulder(2f.pow(lb + gain + shadowLift(lb)), gain)
        var lo = 0f
        var hi = 1f
        repeat(BISECTIONS) {
            val mid = (lo + hi) / 2
            if (ToneCurve.s(mid, params.contrast) < params.blackTarget) lo = mid else hi = mid
        }
        val u = Srgb.decode((lo + hi) / 2)
        return ((v - u) / (1f - u)).coerceIn(0f, MAX_BLACK)
    }

    /** Linear light [x] rolled off above the knee so that 2^[gain] (the brightest input after the gain) lands on 1. */
    private fun shoulder(x: Float, gain: Float): Float {
        val k = params.shoulderKnee
        val maxLinear = 2f.pow(gain)
        if (x <= k || maxLinear <= 1f) return x.coerceIn(0f, 1f)
        val slope = ((maxLinear - k) / (1f - k)).coerceAtLeast(1f)
        val t = ((x - k) / (maxLinear - k)).coerceIn(0f, 1f)
        return k + (1f - k) * (1f - (1f - t).pow(slope))
    }

    private fun finish(v: Float, black: Float): Float {
        val lin = ((Srgb.decode(v) - black) / (1f - black)).coerceIn(0f, 1f)
        return ToneCurve.s(Srgb.encode(lin), params.contrast)
    }

    /** Display output of a neutral pixel of log luma [l] (gain already added): shoulder, black point, S curve. */
    private fun display(l: Float, gain: Float, black: Float): Float = finish(Srgb.encode(shoulder(2f.pow(l), gain)), black)

    /** Stops added to a pixel whose edge-aware base is [base] (log2 linear). */
    fun shadowLift(base: Float): Float {
        val below = params.shadowPivot - base
        if (below <= 0f) return 0f
        val lift = (params.shadowStrength * below).coerceAtMost(params.maxShadowLift)
        return lift * smooth(floor, floor + SHADOW_FADE, base)
    }

    /** Detail gain at [base]: [RenderParams.detail], faded out near black (noise). */
    fun detailGain(base: Float): Float = params.detail * smooth(floor, floor + SHADOW_FADE, base)

    /**
     * Display luma, before the black point and S curve, for a pixel of log luma [l] and base [base]: the colour is
     * scaled by this over its input luma, then [finish] runs per channel.
     */
    fun map(l: Float, base: Float): Float {
        val out = l + whiteGain + shadowLift(base) + detailGain(base) * (l - base)
        val f = ((out - OUT_MIN) / OUT_STEP).coerceIn(0f, OUT_SIZE.toFloat())
        val k = f.toInt().coerceAtMost(OUT_SIZE - 1)
        return table[k] + (table[k + 1] - table[k]) * (f - k)
    }

    /** The black point (in linear light) and the S curve on one display channel. */
    fun finish(v: Float): Float {
        val f = v.coerceIn(0f, 1f) * FINISH_SIZE
        val k = f.toInt().coerceAtMost(FINISH_SIZE - 1)
        return finishTable[k] + (finishTable[k + 1] - finishTable[k]) * (f - k)
    }

    /** Copies of the output and finish tables ([OUT_SIZE] + 1 and [FINISH_SIZE] + 1 entries), for the GPU rendering (D90). */
    fun outTable(): FloatArray = table.copyOf()

    fun finishTable(): FloatArray = finishTable.copyOf()

    companion object {
        const val OUT_MIN = LogLuma.LOG_FLOOR
        private const val OUT_MAX = 3f
        const val OUT_SIZE = 4096
        const val OUT_STEP = (OUT_MAX - OUT_MIN) / OUT_SIZE
        const val FINISH_SIZE = 4096
        const val SHADOW_FADE_STOPS = 2f
        private const val BISECTIONS = 24
        private const val SHADOW_FADE = SHADOW_FADE_STOPS

        /** Most linear light the black point removes (about display 0.15, D74's cap). */
        const val MAX_BLACK = 0.02f

        fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
