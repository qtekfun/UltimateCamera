package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraShapes
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import java.util.Locale

internal val OK_WHITE = Color.White
internal val WARN_YELLOW = Color(0xFFFFC107)
internal val HOT_ORANGE = Color(0xFFFF7043)
internal val ALERT_RED = Color(0xFFE53935)

/**
 * The camera screen's take bar (D47): while recording the dropped frames of the take and the written bitrate; the GPU
 * stage's p95 frame time is always shown. Battery, heat and storage moved to [HealthIndicators] (D52). Fed by
 * [RecordingMonitor] at 2 Hz.
 */
@Composable
fun StatusBar(status: StatusBarState, modifier: Modifier = Modifier, proxy: Boolean = false) {
    val items = buildList {
        if (proxy) add(stringResource(R.string.status_proxy) to OK_WHITE)
        if (status.recording) {
            add(stringResource(R.string.status_dropped, status.droppedFrames) to if (status.droppedFrames > 0) ALERT_RED else OK_WHITE)
            add(stringResource(R.string.status_bitrate, formatMbps(status.bitrateBps)) to OK_WHITE)
        }
        add(stringResource(R.string.status_gpu_p95, formatTenths(status.renderP95TenthsMs)) to OK_WHITE)
    }
    // One text with coloured spans: it wraps on a narrow portrait screen instead of running off the edge.
    val text = buildAnnotatedString {
        items.forEachIndexed { index, (part, color) ->
            if (index > 0) withStyle(SpanStyle(color = OK_WHITE)) { append(SEPARATOR) }
            withStyle(SpanStyle(color = color)) { append(part) }
        }
    }
    Text(
        text,
        style = CameraTheme.type.label,
        modifier = modifier
            .clip(CameraShapes.pill)
            .background(CameraTheme.colors.scrim)
            .border(CameraTheme.hairlineBorder, CameraShapes.pill)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@StringRes
internal fun thermalLabel(status: Int): Int = when {
    status >= PowerManager.THERMAL_STATUS_CRITICAL -> R.string.status_thermal_critical
    status >= PowerManager.THERMAL_STATUS_SEVERE -> R.string.status_thermal_severe
    status >= PowerManager.THERMAL_STATUS_MODERATE -> R.string.status_thermal_moderate
    else -> R.string.status_thermal_normal
}

internal fun thermalColor(status: Int): Color = when {
    status >= PowerManager.THERMAL_STATUS_CRITICAL -> ALERT_RED
    status >= PowerManager.THERMAL_STATUS_SEVERE -> HOT_ORANGE
    status >= PowerManager.THERMAL_STATUS_MODERATE -> WARN_YELLOW
    else -> OK_WHITE
}

/** Megabits per second with one decimal, e.g. `98.4`. */
internal fun formatMbps(bps: Long): String = String.format(Locale.ROOT, "%.1f", bps / BITS_PER_MEGABIT)

/** Tenths of a millisecond as `12.3`. */
internal fun formatTenths(tenths: Int): String = "${tenths / TENTHS}.${tenths % TENTHS}"

/** The note shown when the guard stopped a take: why, and the file that was saved. */
@StringRes
internal fun stopReasonText(reason: StopReason): Int = when (reason) {
    StopReason.THERMAL -> R.string.camera_stopped_thermal
    StopReason.BATTERY -> R.string.camera_stopped_battery
    StopReason.STORAGE_FULL -> R.string.camera_stopped_storage
}

private const val BITS_PER_MEGABIT = 1_000_000.0
private const val TENTHS = 10
private const val SEPARATOR = "  ·  "
