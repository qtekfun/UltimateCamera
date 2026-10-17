package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale

private val OK_WHITE = Color.White
private val WARN_YELLOW = Color(0xFFFFC107)
private val HOT_ORANGE = Color(0xFFFF7043)
private val ALERT_RED = Color(0xFFE53935)
private val BAR_SCRIM = Color(0x99000000)

/**
 * The camera screen's status bar (D47): battery and charging, heat (colour coded), and while recording the dropped
 * frames of the take, the written bitrate and the minutes left; the GPU stage's p95 frame time is always shown.
 * Fed by [RecordingMonitor] at 2 Hz.
 */
@Composable
fun StatusBar(status: StatusBarState, minutesLeft: Long?, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(BAR_SCRIM).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status.batteryPercent >= 0) {
            val low = !status.charging && status.batteryPercent <= LOW_BATTERY_WARN_PERCENT
            val text = stringResource(if (status.charging) R.string.status_battery_charging else R.string.status_battery, status.batteryPercent)
            Item(text, if (low) ALERT_RED else OK_WHITE)
        }
        Item(stringResource(thermalLabel(status.thermalStatus)), thermalColor(status.thermalStatus))
        if (status.recording) {
            Item(stringResource(R.string.status_dropped, status.droppedFrames), if (status.droppedFrames > 0) ALERT_RED else OK_WHITE)
            Item(stringResource(R.string.status_bitrate, formatMbps(status.bitrateBps)), OK_WHITE)
            minutesLeft?.let { Item(stringResource(R.string.status_minutes_left, it), if (it < LOW_MINUTES) WARN_YELLOW else OK_WHITE) }
        }
        Item(stringResource(R.string.status_gpu_p95, formatTenths(status.renderP95TenthsMs)), OK_WHITE)
    }
}

@Composable
private fun Item(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium)
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

private const val LOW_BATTERY_WARN_PERCENT = 15
private const val LOW_MINUTES = 10L
private const val BITS_PER_MEGABIT = 1_000_000.0
private const val TENTHS = 10
