package com.qtekfun.ultimatecamera.feature.camera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The camera screen's design system (D49): a dark, translucent "field monitor" layer drawn over any image.
 *
 * Surfaces are flat scrims (no blur: blur costs a full-screen pass on modest phones and smears the image being judged).
 * Status colours are fixed so REC is always the same red whatever the wallpaper; the accent (selected chips, active
 * tools) comes from Material 3 dynamic colour.
 */
@Immutable
data class CameraColors(
    /** Selected chip, active tool, mode indicator: the dynamic primary. */
    val accent: Color,
    val onAccent: Color,
    /** REC fill, meter segments, dots: graphics (3:1 is enough). Text in the alert tone uses [alertText]. */
    val rec: Color = Color(0xFFFF3B30),
    /** Amber: graphics and text (4.7:1 over [scrim] on a white image, see ContrastTest). */
    val warning: Color = Color(0xFFFFB020),
    /** Green for graphics (meter, link dot). Text in the OK tone uses [okText]. */
    val ok: Color = Color(0xFF30D158),
    /** Green for text: ≥ 4.5:1 over [scrim] even on a white sky (D67). */
    val okText: Color = Color(0xFF6EEA92),
    /** Red for text (alerts, dropped frames, failures): ≥ 4.5:1 over [scrim] even on a white sky (D67). */
    val alertText: Color = Color(0xFFFFB3AD),
    /**
     * Default overlay surface: strong enough for every text token at ≥ 4.5:1 over a white sky (D67; 0x99 gave only 3.8:1
     * for muted text), light enough to see through.
     */
    val scrim: Color = Color(0xB3000000),
    /** Panels and toasts that hold many controls. */
    val scrimStrong: Color = Color(0xE0121214),
    /** Selected item inside a scrim when the accent would be too loud (e.g. inactive segment hover). */
    val scrimRaised: Color = Color(0x33FFFFFF),
    val hairline: Color = Color(0x26FFFFFF),
    val content: Color = Color.White,
    val contentMuted: Color = Color(0xD9FFFFFF),
    /** Disabled controls only (WCAG exempts inactive components from the text contrast minimum). */
    val contentDisabled: Color = Color(0x61FFFFFF),
    /** Health banner, warning line: black text on amber. */
    val bannerWarning: Color = Color(0xEEFFB300),
    /** Health banner, critical line: white text on deep red (≥ 4.5:1 over any image; 0xEED32F2F was 4.58 at best). */
    val bannerCritical: Color = Color(0xF0B71C1C)
)

/** Shapes: 16 dp for surfaces, full pills for chips and strips, 8 dp for small badges. */
object CameraShapes {
    val surface = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(percent = 50)
    val badge = RoundedCornerShape(6.dp)
}

/** Sizes shared by every control. 48 dp is the minimum touch target; the REC button is bigger on purpose. */
object CameraDimens {
    val touch = 48.dp
    val icon = 22.dp
    val rec = 76.dp
    val gap = 8.dp
    val edge = 12.dp
}

/** Text styles. Numbers use tabular figures so readouts never jitter while they change. */
@Immutable
data class CameraType(
    /** The running take time, monospaced like a camera's timecode display. */
    val timecode: TextStyle,
    /** Values: ISO 800, 1/50, 5600K, 4K30. */
    val value: TextStyle,
    /** Small caps labels above values and in badges: ISO, SHUTTER, HEVC. */
    val label: TextStyle,
    /** Running text inside panels and toasts. */
    val body: TextStyle,
    /** Panel and section titles. */
    val title: TextStyle
)

private const val TABULAR = "tnum"

/** A soft shadow keeps text readable where it is drawn without a scrim. */
private val TextShadow = Shadow(color = Color(0x99000000), offset = Offset(0f, 1f), blurRadius = 3f)

private val DefaultCameraType = CameraType(
    timecode = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR,
        shadow = TextShadow
    ),
    value = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, fontFeatureSettings = TABULAR, shadow = TextShadow),
    label = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp, fontFeatureSettings = TABULAR, shadow = TextShadow),
    body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    title = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.6.sp)
)

private val LocalCameraColors = staticCompositionLocalOf<CameraColors?> { null }
private val LocalCameraType = staticCompositionLocalOf { DefaultCameraType }

/** Entry point for the tokens: `CameraTheme.colors.rec`, `CameraTheme.type.timecode`. */
object CameraTheme {
    val colors: CameraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCameraColors.current ?: fromMaterial()

    val type: CameraType
        @Composable
        @ReadOnlyComposable
        get() = LocalCameraType.current

    val hairlineBorder: BorderStroke
        @Composable
        @ReadOnlyComposable
        get() = BorderStroke(1.dp, colors.hairline)

    @Composable
    @ReadOnlyComposable
    private fun fromMaterial(): CameraColors = CameraColors(accent = MaterialTheme.colorScheme.primary, onAccent = MaterialTheme.colorScheme.onPrimary)
}

/** Provides the camera tokens and the system's reduced-motion setting to [content]; wraps the camera screen and its panels (works without it too). */
@Composable
fun CameraTheme(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(
        LocalCameraColors provides CameraColors(accent = scheme.primary, onAccent = scheme.onPrimary),
        LocalCameraType provides DefaultCameraType
    ) {
        // Remove animations (animator scale 0) turns the chip fades, REC morph and REC pulse off (D67).
        ProvideReducedMotion(rememberSystemReducedMotion(), content)
    }
}
