package com.qtekfun.ultimatecamera.feature.camera.ui

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Accessibility helpers of the camera design system (D67): WCAG contrast over the scrims, reduced motion, capped font
 * scale for tabular numbers and the throttled spoken take time.
 */

/** WCAG 2.x relative luminance of an opaque sRGB colour (alpha ignored: composite first with [over]). */
fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double = if (c <= SRGB_KNEE) c / SRGB_LINEAR_DIV else ((c + SRGB_OFFSET) / SRGB_SCALE).toDouble().pow(SRGB_GAMMA)
    return LUM_R * channel(color.red) + LUM_G * channel(color.green) + LUM_B * channel(color.blue)
}

/** [top] drawn with its alpha over the opaque [bottom] (source-over), as the GPU composites a scrim over the image. */
fun over(top: Color, bottom: Color): Color {
    val a = top.alpha
    return Color(
        red = top.red * a + bottom.red * (1 - a),
        green = top.green * a + bottom.green * (1 - a),
        blue = top.blue * a + bottom.blue * (1 - a),
        alpha = 1f
    )
}

/** WCAG contrast ratio (1..21) of two opaque colours. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + FLARE) / (min(la, lb) + FLARE)
}

/**
 * Contrast of [text] on [surface] (both possibly translucent) drawn over [image]: the scrim is composited over the
 * image first, then the text over that. A white sky is the worst case for every light text token.
 */
fun contrastOverImage(text: Color, surface: Color, image: Color): Double {
    val background = over(surface, image)
    return contrastRatio(over(text, background), background)
}

/** The WCAG AA minimum for normal text. */
const val MIN_TEXT_CONTRAST = 4.5

/** True when the system animator duration scale is 0 (Developer options or Accessibility › Remove animations). */
fun animationsOff(animatorScale: Float): Boolean = animatorScale == 0f

private val LocalReducedMotion = compositionLocalOf { false }

/** Reduced motion as read from the system once per [CameraTheme]; tests can provide it with [ProvideReducedMotion]. */
object CameraMotion {
    val reduced: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReducedMotion.current

    /** Animation for colour and size changes: a spring, or an immediate snap when motion is reduced. */
    @Composable
    @ReadOnlyComposable
    fun <T> spec(): AnimationSpec<T> = if (reduced) snap() else spring()
}

@Composable
internal fun rememberSystemReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { animationsOff(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)) }
}

/** Provides [reduced] to the camera components (the camera theme provides the system value). */
@Composable
fun ProvideReducedMotion(reduced: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides reduced, content = content)
}

/** The font scale tabular numbers use: the system scale up to [cap] (they shrink instead of clipping; labels wrap). */
fun cappedFontScale(system: Float, cap: Float = NUMBER_FONT_SCALE_CAP): Float = min(system, cap)

/**
 * Draws [content] with the font scale capped by [cappedFontScale]. Only for one-line tabular readouts (timecode,
 * badges, values) whose width must stay bounded on the overlay; running text and labels keep the full scale and wrap.
 */
@Composable
fun CappedFontScale(cap: Float = NUMBER_FONT_SCALE_CAP, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scale = cappedFontScale(density.fontScale, cap)
    if (scale == density.fontScale) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
    }
}

/** Take time for speech, `HH:MM:SS`, floored to [stepMs] so a changing value is not re-announced every frame. */
fun spokenElapsed(elapsedMs: Long, stepMs: Long = 1_000L): String {
    val ms = elapsedMs.coerceAtLeast(0)
    val stepped = if (stepMs > 0) ms - ms % stepMs else ms
    val s = stepped / MS_PER_S
    return "%02d:%02d:%02d".format(s / S_PER_H, s % S_PER_H / S_PER_MIN, s % S_PER_MIN)
}

/** The status strip's live region speaks the take time once a minute (polite, throttled). */
const val LIVE_TIMECODE_STEP_MS = 60_000L

/** At 200 % system font scale tabular numbers grow to 150 %. */
const val NUMBER_FONT_SCALE_CAP = 1.5f

private const val SRGB_KNEE = 0.04045f
private const val SRGB_LINEAR_DIV = 12.92
private const val SRGB_OFFSET = 0.055f
private const val SRGB_SCALE = 1.055f
private const val SRGB_GAMMA = 2.4
private const val LUM_R = 0.2126
private const val LUM_G = 0.7152
private const val LUM_B = 0.0722
private const val FLARE = 0.05
private const val MS_PER_S = 1000L
private const val S_PER_MIN = 60L
private const val S_PER_H = 3600L
