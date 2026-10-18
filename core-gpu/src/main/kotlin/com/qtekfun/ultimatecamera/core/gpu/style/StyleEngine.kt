package com.qtekfun.ultimatecamera.core.gpu.style

import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.LutCompose
import com.qtekfun.ultimatecamera.core.gpu.LutMath
import com.qtekfun.ultimatecamera.core.gpu.UcLog
import com.qtekfun.ultimatecamera.core.gpu.UcLogLuts
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** What a style LUT is applied to: display-referred Rec.709 (photos, 8-bit video, a baked LUT's output), UC-Log or HLG. */
enum class StyleInput { REC709, UC_LOG, HLG }

/**
 * The style engine shared by photo and video (D79). A style is a pure function of display-referred Rec.709 values
 * ([transform]); [lutFor] samples it on a 33³ lattice for the GPU stage, and [applyStyle] runs it on the CPU for photos.
 *
 * The maths, per colour (all in the encoded 0..1 signal):
 * 1. Luma `Y` (BT.709 weights) goes through a tone curve `f(Y) = lift + (1 − lift − roll) · (Y + c · (smoothstep(Y) − Y))`
 *    with contrast `c` in −0.6..0.6: monotonic (its slope is at least `1 − |c|` > 0), 0 and 1 stay fixed apart from the
 *    film fade, and a positive `c` is also the highlight shoulder.
 * 2. Chroma `C = RGB − Y` (zero luma) is scaled: saturation boosts are vibrance-like (weaker on already saturated colours),
 *    fade out over the highlights (no colour clipping near white) and are 60 % weaker on skin hues; cuts are uniform.
 * 3. Warmth adds a zero-luma amber/blue vector weighted by `4Y(1 − Y)` (blacks and whites stay neutral), half as strong
 *    on skin.
 * 4. Out-of-gamut results are brought back by shrinking the chroma towards `f(Y)`, never by clipping channels, so the
 *    hue stays and gradients never flatten; the output luma is exactly `f(Y)`.
 * 5. [StyleParams.intensity] blends with the input.
 *
 * Skin hue: the angle of (B − Y, R − Y) of typical skin tones from very light to very dark is 125°–135°; the protection
 * weight is a Gaussian of 22° around 130°.
 */
object StyleEngine {
    const val LUT_SIZE = 33

    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f

    /** Amber (+R, −B) with its luma removed, so warmth never changes brightness. */
    private val WARM = floatArrayOf(1f - (KR - KB), -(KR - KB), -1f - (KR - KB))
    private const val WARMTH_SCALE = 0.05f
    private const val SKIN_HUE_DEG = 130f
    private const val SKIN_WIDTH_DEG = 22f
    private const val MAX_CONTRAST = 0.6f

    private val cache = object : LinkedHashMap<Pair<StyleParams, StyleInput>, CubeLut>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<StyleParams, StyleInput>, CubeLut>?) = size > CACHE_SIZE
    }
    private const val CACHE_SIZE = 6

    /**
     * The 33³ LUT of [params] for [input]: Rec.709 directly, UC-Log after the built-in UC-Log → Rec.709 viewing LUT, HLG
     * after HLG → UC-Log → Rec.709 (the same transforms the monitor uses, chained with [LutCompose]). Cached (a few
     * entries), deterministic.
     */
    @Synchronized
    fun lutFor(params: StyleParams, input: StyleInput = StyleInput.REC709): CubeLut = cache.getOrPut(params to input) {
        val style = sample(params, "Style ${params.preset.id} (UltimateCamera)")
        when (input) {
            StyleInput.REC709 -> style
            StyleInput.UC_LOG -> LutCompose.compose(UcLogLuts.toRec709(LUT_SIZE), style, LUT_SIZE)
            StyleInput.HLG -> {
                val toLog = CubeLut.generate(LUT_SIZE, "HLG to UC-Log") { r, g, b -> Triple(UcLog.fromHlg(r), UcLog.fromHlg(g), UcLog.fromHlg(b)) }
                LutCompose.compose(LutCompose.compose(toLog, UcLogLuts.toRec709(LUT_SIZE), LUT_SIZE), style, LUT_SIZE)
            }
        }
    }

    /**
     * [transform] on the [LUT_SIZE]³ lattice, the same values in the same layout as [CubeLut.generate], one blue slice
     * per job on all cores (D90: 0.2 s on one core for the first photo after start, which runs it interpreted).
     */
    private fun sample(params: StyleParams, title: String): CubeLut {
        val n = LUT_SIZE
        val data = FloatArray(n * n * n * 3)
        val step = 1f / (n - 1)
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            val jobs = (0 until n).map { b ->
                Callable {
                    val out = FloatArray(3)
                    var i = b * n * n * 3
                    for (g in 0 until n) {
                        for (r in 0 until n) {
                            transform(params, r * step, g * step, b * step, out)
                            data[i++] = out[0]
                            data[i++] = out[1]
                            data[i++] = out[2]
                        }
                    }
                }
            }
            pool.invokeAll(jobs).forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return CubeLut(n, data, title)
    }

    /**
     * Applies [params] in place to ARGB_8888 [pixels] (alpha kept) through its LUT, the same trilinear lookup the GPU does.
     * This is what the photo pipeline calls last, on a strip or a whole bitmap's pixels; identity styles return at once.
     */
    fun applyStyle(pixels: IntArray, params: StyleParams, offset: Int = 0, length: Int = pixels.size - offset) {
        if (params.isIdentity) return
        val lut = lutFor(params)
        val out = FloatArray(3)
        for (i in offset until offset + length) {
            val p = pixels[i]
            LutMath.apply(lut, ((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f, out)
            pixels[i] = (p and 0xFF000000.toInt()) or (out[0].to8() shl 16) or (out[1].to8() shl 8) or out[2].to8()
        }
    }

    /** The exact style of one Rec.709 colour (0..1 in and out) into [out]. */
    fun transform(params: StyleParams, r: Float, g: Float, b: Float, out: FloatArray) {
        if (params.isIdentity) {
            out[0] = r
            out[1] = g
            out[2] = b
            return
        }
        val look = params.preset.look
        val y = KR * r + KG * g + KB * b
        val yOut = toneCurve(look, params.tone, y)
        val cr = r - y
        val cg = g - y
        val cb = b - y
        val skin = skinWeight(cr, cb)
        val chroma = max(abs(cr), max(abs(cg), abs(cb)))
        val scale = chromaScale(look, params.saturation, chroma, skin, yOut)
        val warmth = (look.warmth + 0.5f * params.warmth).coerceIn(-1f, 1f) * WARMTH_SCALE * (4f * yOut * (1f - yOut)) * (1f - 0.5f * skin)
        val dr = cr * scale + WARM[0] * warmth
        val dg = cg * scale + WARM[1] * warmth
        val db = cb * scale + WARM[2] * warmth
        val t = min(1f, min(fit(yOut, dr), min(fit(yOut, dg), fit(yOut, db))))
        val k = params.intensity
        out[0] = r + (yOut + dr * t - r) * k
        out[1] = g + (yOut + dg * t - g) * k
        out[2] = b + (yOut + db * t - b) * k
    }

    /** The tone curve of the luma (exposed for the tests): monotonic, see the class comment. */
    fun toneCurve(look: StyleLook, tone: Float, y: Float): Float {
        val c = (look.contrast + 0.35f * tone).coerceIn(-MAX_CONTRAST, MAX_CONTRAST)
        val lift = look.fade + 0.04f * max(0f, -tone)
        val roll = 0.5f * look.fade
        val x = y.coerceIn(0f, 1f)
        val s = x * x * (3f - 2f * x)
        return lift + (1f - lift - roll) * (x + c * (s - x))
    }

    /** 0..1: how much a colour with chroma (R − Y, B − Y) looks like skin. */
    fun skinWeight(cr: Float, cb: Float): Float {
        val chroma = max(abs(cr), abs(cb))
        if (chroma < 1e-4f) return 0f
        val hue = Math.toDegrees(atan2(cr, cb).toDouble()).toFloat()
        val d = (hue - SKIN_HUE_DEG) / SKIN_WIDTH_DEG
        return exp(-d * d) * min(1f, chroma / 0.03f)
    }

    private fun chromaScale(look: StyleLook, padSaturation: Float, chroma: Float, skin: Float, yOut: Float): Float {
        if (look.mono) return 0f
        val s = (look.saturation + 0.5f * padSaturation).coerceIn(-1f, 1f)
        if (s <= 0f) return 1f + s
        val vibrance = 1f - 0.5f * min(1f, chroma / 0.5f)
        val highlights = 1f - smoothstep(0.7f, 1f, yOut)
        return 1f + s * vibrance * highlights * (1f - 0.6f * skin)
    }

    /** The largest t ≤ 1 with y + t · d inside 0..1. */
    private fun fit(y: Float, d: Float): Float = when {
        d > 1e-6f -> (1f - y) / d
        d < -1e-6f -> y / -d
        else -> 1f
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun Float.to8(): Int = (coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
