package com.qtekfun.ultimatecamera.core.gpu.style

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The fixed character of a style preset (D74): what the pad and the intensity slider start from. All values are in
 * −1..+1 except [fade] (0..0.2, black lift) and [mono] (no colour at all).
 */
data class StyleLook(val contrast: Float = 0f, val warmth: Float = 0f, val saturation: Float = 0f, val fade: Float = 0f, val mono: Boolean = false)

/**
 * The named presets. [id] is persisted (look tokens, XMP), so it never changes. Names are neutral on purpose: no
 * maker's or film stock's name (F-Droid and trademarks).
 */
enum class StylePreset(val id: String, val look: StyleLook) {
    STANDARD("standard", StyleLook()),
    RICH_CONTRAST("rich", StyleLook(contrast = 0.32f, saturation = 0.08f)),
    VIBRANT("vibrant", StyleLook(contrast = 0.1f, saturation = 0.45f)),
    WARM("warm", StyleLook(contrast = 0.05f, warmth = 0.5f, saturation = 0.05f)),
    COOL("cool", StyleLook(contrast = 0.05f, warmth = -0.5f)),
    NATURAL("natural", StyleLook(contrast = -0.12f, saturation = -0.12f)),
    SOFT_FILM("soft_film", StyleLook(contrast = 0.12f, warmth = 0.15f, saturation = -0.22f, fade = 0.04f)),
    FADED("faded", StyleLook(contrast = -0.15f, warmth = 0.05f, saturation = -0.35f, fade = 0.1f)),
    MONO("mono", StyleLook(contrast = 0.08f, mono = true)),
    MONO_CONTRAST("mono_contrast", StyleLook(contrast = 0.45f, mono = true));

    companion object {
        fun byId(id: String): StylePreset? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A style as the user tunes it (D74): a [preset] plus the pad and slider. [tone] (−1..+1) lowers or raises contrast
 * (negative also lifts the shadows), [warmth] (−1..+1) moves towards blue or amber, [saturation] (−1..+1) mutes or
 * enriches the palette, and [intensity] (0..1) blends the result with the untouched image (0 = identity). Values are
 * clamped and quantised to 0.01 so the token round-trips exactly and equal styles generate equal LUTs.
 */
class StyleParams(val preset: StylePreset, tone: Float = 0f, warmth: Float = 0f, saturation: Float = 0f, intensity: Float = 1f) {
    val tone: Float = quantise(tone, -1f)
    val warmth: Float = quantise(warmth, -1f)
    val saturation: Float = quantise(saturation, -1f)
    val intensity: Float = quantise(intensity, 0f)

    /** True when the style leaves every colour as it is: no LUT is needed. */
    val isIdentity: Boolean
        get() = intensity == 0f || (preset == StylePreset.STANDARD && tone == 0f && warmth == 0f && saturation == 0f)

    /** `style:<id>:<tone>,<warmth>,<saturation>,<intensity>`, two decimals, Locale.ROOT. */
    val token: String
        get() = "$PREFIX${preset.id}:" + listOf(tone, warmth, saturation, intensity).joinToString(",") { String.format(Locale.ROOT, "%.2f", it) }

    fun copy(
        preset: StylePreset = this.preset,
        tone: Float = this.tone,
        warmth: Float = this.warmth,
        saturation: Float = this.saturation,
        intensity: Float = this.intensity
    ) = StyleParams(preset, tone, warmth, saturation, intensity)

    override fun equals(other: Any?): Boolean = other is StyleParams && other.token == token

    override fun hashCode(): Int = token.hashCode()

    override fun toString(): String = token

    companion object {
        const val PREFIX = "style:"

        /** The preset at its defaults (pad centred, full intensity). */
        fun of(preset: StylePreset) = StyleParams(preset)

        /** Parses [token]; null when it is not a style token or names an unknown preset. Missing numbers read as defaults. */
        fun parse(token: String?): StyleParams? {
            if (token == null || !token.startsWith(PREFIX)) return null
            val rest = token.removePrefix(PREFIX)
            val preset = StylePreset.byId(rest.substringBefore(':')) ?: return null
            val numbers = rest.substringAfter(':', "").split(',').map { it.trim().toFloatOrNull() }
            fun at(i: Int, default: Float) = numbers.getOrNull(i)?.takeIf { it.isFinite() } ?: default
            return StyleParams(preset, at(0, 0f), at(1, 0f), at(2, 0f), at(3, 1f))
        }

        private fun quantise(v: Float, min: Float): Float {
            val q = ((if (v.isNaN()) 0f else v.coerceIn(min, 1f)) * 100f).roundToInt() / 100f
            return if (abs(q) < 0.005f) 0f else q
        }
    }
}
