package com.qtekfun.ultimatecamera.core.gpu

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * UC-Log, the project's own Log encoding (REQ-COL-002), applied on the GPU to the camera's 10-bit signal. This object
 * is the CPU specification; [UcLogShaders] is the same maths in GLSL and the tests pin one to the other.
 *
 * `x` is scene-linear light normalised so an 18 % grey card is 0.18; `y` is the full-range signal in 0..1 (10-bit
 * code = `y · 1023`).
 *
 * ```
 * y = 0.40 + 0.10 · log2((x + 1/64) / (0.18 + 1/64))     x ≥ 0
 * y = Y0 + S0 · x                                       x < 0   (linear toe, keeps negative noise invertible)
 * Y0 = y(0) = 0.40 − 0.10 · log2(12.52) ≈ 0.035384,  S0 = y'(0) = 0.10 / ((1/64) · ln 2) ≈ 9.2332
 * ```
 * Inverse: `x = (0.18 + 1/64) · 2^((y − 0.40) / 0.10) − 1/64` for `y ≥ Y0`, `x = (y − Y0) / S0` below.
 *
 * | Point | Linear x | Signal y | 10-bit code |
 * |---|---|---|---|
 * | Black | 0 | 0.035384 | 36 |
 * | 18 % grey | 0.18 | 0.400000 | 409 |
 * | 90 % white | 0.90 | 0.622667 | 637 |
 * | Clip | 12.504375 (+6.12 stops over grey) | 1.0 | 1023 |
 *
 * Why these parameters (documented in docs/PROGRESS.md D26): 0.10 per stop is 102 codes per stop of `x + 1/64` in
 * 10-bit, 94 at grey and still 76 two stops under it, so one code step is ≤ 1 % of linear light from −2 stops to clip
 * (0.69–0.92 %) and ≤ 2 % down to about −4.5 stops, under the ≈ 1–2 % visibility threshold for a smooth gradient after a normal
 * grade: no banding in HEVC 10-bit (REQ-COL-005). The same curve in 8-bit would be 26 codes per stop (≈ 2.7 % steps) and
 * band, so UC-Log is a 10-bit-only option. The 1/64 offset bends the curve into a near-linear toe below about
 * −3.5 stops where phone sensors are noise, instead of spending codes on it, and lifts black to code 36 so negative
 * noise and the encoder's rounding have room. The clip at 12.5 holds the brightest HLG signal (≈ 3.74, code 845) with
 * 1.74 stops to spare for RAW or vendor HDR sources later (Phase 8).
 */
object UcLog {
    const val NAME = "UC-Log"

    /** Signal of 18 % grey. */
    const val GREY_SIGNAL = 0.40f

    /** Signal per stop (per doubling of `x + OFFSET`). */
    const val STOP_SLOPE = 0.10f

    /** Linear offset that shapes the toe. */
    const val OFFSET = 1f / 64f

    const val GREY = 0.18f

    private const val ANCHOR = GREY + OFFSET
    private val LN2 = ln(2.0)

    /** `y(0)`: the signal of linear black. */
    val BLACK_SIGNAL: Float = (GREY_SIGNAL + STOP_SLOPE * log2(OFFSET.toDouble() / ANCHOR)).toFloat()

    /** Slope of the curve at 0, continued linearly below it. */
    val TOE_SLOPE: Float = (STOP_SLOPE / (OFFSET * LN2)).toFloat()

    /** Linear value that encodes to signal 1.0. */
    val MAX_LINEAR: Float = decode(1f)

    fun encode(x: Float): Float = if (x >= 0f) (GREY_SIGNAL + STOP_SLOPE * log2((x.toDouble() + OFFSET) / ANCHOR)).toFloat() else BLACK_SIGNAL + TOE_SLOPE * x

    fun decode(y: Float): Float =
        if (y >= BLACK_SIGNAL) (ANCHOR * 2.0.pow((y.toDouble() - GREY_SIGNAL) / STOP_SLOPE) - OFFSET).toFloat() else (y - BLACK_SIGNAL) / TOE_SLOPE

    /** 10-bit full-range code of [x], rounded and clamped to 0..1023. */
    fun code10(x: Float): Int = (encode(x).coerceIn(0f, 1f) * 1023f + 0.5f).toInt()

    // Input transfer functions: the camera delivers HLG (10-bit HLG10 profile) or a BT.709-like SDR signal.

    /** BT.2100 HLG inverse OETF: signal 0..1 → scene light 0..1 (HLG's own normalisation, peak 1.0). */
    fun hlgToLinear(e: Float): Float {
        val v = e.coerceAtLeast(0f).toDouble()
        return if (v <= 0.5) (v * v / 3.0).toFloat() else ((exp((v - HLG_C) / HLG_A) + HLG_B) / 12.0).toFloat()
    }

    /**
     * Scale from HLG scene light to UC-Log's normalisation: BT.2408 places an 18 % grey card at 38 % HLG signal, i.e.
     * `0.38² / 3 = 0.048133`, which must become 0.18. HLG peak lands at 3.74 (+4.4 stops over grey).
     */
    val HLG_SCALE: Float = (GREY / (HLG_GREY_SIGNAL * HLG_GREY_SIGNAL / 3.0)).toFloat()

    /** BT.709 inverse OETF (the camera's SDR signal): 0..1 → scene light 0..1, grey 0.18 at signal ≈ 0.409. */
    fun bt709ToLinear(v: Float): Float {
        val s = v.coerceAtLeast(0f).toDouble()
        return if (s < BT709_BREAK * 4.5) (s / 4.5).toFloat() else ((s + 0.099) / 1.099).pow(1.0 / 0.45).toFloat()
    }

    fun fromHlg(signal: Float): Float = encode(hlgToLinear(signal) * HLG_SCALE)

    fun fromBt709(signal: Float): Float = encode(bt709ToLinear(signal))

    /** Signal-domain encoder for an input transfer. */
    fun encodeFrom(source: LogSource, signal: Float): Float = when (source) {
        LogSource.HLG -> fromHlg(signal)
        LogSource.BT709 -> fromBt709(signal)
    }

    /** Applies [encodeFrom] to every channel of an ARGB_8888 image, rounding to 8 bits like an RGBA8 framebuffer. */
    fun encodeArgb(source: LogSource, pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
        val p = pixels[i]
        fun ch(shift: Int): Int = (encodeFrom(source, ((p shr shift) and 0xFF) / 255f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        (p and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    internal const val HLG_A = 0.17883277
    internal const val HLG_B = 0.28466892
    internal const val HLG_C = 0.55991073
    internal const val HLG_GREY_SIGNAL = 0.38
    internal const val BT709_BREAK = 0.018
}

/** The camera signal the Log stage starts from. */
enum class LogSource {
    /** BT.2100 HLG (the 10-bit HLG10 dynamic range profile). */
    HLG,

    /** BT.709 SDR transfer. */
    BT709
}

/** Display gamuts the conversion LUTs know. */
enum class Gamut {
    BT709,
    BT2020
}

/** UC-Log conversion LUTs the app ships as defaults (own work, no third-party licence: REQ-COL-008). */
object UcLogLuts {
    /**
     * UC-Log → Rec.709 for viewing and quick delivery. Per channel: decode UC-Log, convert the [source] primaries to
     * BT.709 (BT.2020 → BT.709 matrix, negatives clipped), keep linear light up to [KNEE] and roll the highlights off
     * above it towards 1.0 with `k + (1 − k) · t / (1 + t)`, `t = (x − k) / (1 − k)` (continuous slope at the knee), then
     * encode with the BT.709 OETF, as a broadcast camera does: on a BT.1886 (gamma 2.4) display that gives the usual
     * camera system gamma of about 1.2, and its linear toe keeps the LUT accurate near black (a pure 1/2.4 power has an
     * infinite slope at 0 that a 33-point lattice cannot follow). 18 % grey maps to 0.409 (code 418 of 1023).
     */
    fun toRec709(size: Int = 33, source: Gamut = Gamut.BT2020): CubeLut {
        val lin = DoubleArray(3)
        return CubeLut.generate(size, "UC-Log ${source.label} to Rec.709 (UltimateCamera)") { r, g, b ->
            lin[0] = max(0.0, UcLog.decode(r).toDouble())
            lin[1] = max(0.0, UcLog.decode(g).toDouble())
            lin[2] = max(0.0, UcLog.decode(b).toDouble())
            val (lr, lg, lb) = if (source == Gamut.BT2020) bt2020To709(lin) else Triple(lin[0], lin[1], lin[2])
            Triple(display(lr), display(lg), display(lb))
        }
    }

    /** The identity in UC-Log: grading starts from the Log image itself (and the dual-file mode's flat recording). */
    fun identity(size: Int = 33): CubeLut = CubeLut.generate(size, "UC-Log identity (UltimateCamera)") { r, g, b -> Triple(r, g, b) }

    /** The display-referred transform of one linear channel (exposed for the tests). */
    fun display(linear: Double): Float {
        val x = max(0.0, linear)
        val d = if (x <= KNEE) x else KNEE + (1 - KNEE) * ((x - KNEE) / (1 - KNEE)).let { it / (1 + it) }
        return (if (d < UcLog.BT709_BREAK) 4.5 * d else 1.099 * d.pow(0.45) - 0.099).toFloat()
    }

    private fun bt2020To709(c: DoubleArray): Triple<Double, Double, Double> = Triple(
        max(0.0, 1.6605 * c[0] - 0.5876 * c[1] - 0.0728 * c[2]),
        max(0.0, -0.1246 * c[0] + 1.1329 * c[1] - 0.0083 * c[2]),
        max(0.0, -0.0182 * c[0] - 0.1006 * c[1] + 1.1187 * c[2])
    )

    private val Gamut.label get() = if (this == Gamut.BT2020) "BT.2020" else "BT.709"

    const val KNEE = 0.5
}
