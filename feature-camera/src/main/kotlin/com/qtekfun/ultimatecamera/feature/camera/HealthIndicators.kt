package com.qtekfun.ultimatecamera.feature.camera

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraDimens
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraShapes
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import java.util.Locale

/**
 * Always-visible health indicators (D52), also between takes, as one compact line: battery % (⚡ while charging),
 * temperature (battery °C coloured by the thermal status, with the thermal headroom as a small bar where the phone
 * reports it) and recording minutes left on the effective location (USB drives included). A tap shows the details:
 * battery minutes left at the drain measured this session ("—" until there is enough data), headroom and free GB.
 * Self-contained: place it with [modifier].
 */
@Composable
fun HealthIndicators(health: HealthState, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    var expanded by rememberSaveable { mutableStateOf(false) }
    val heatColor = thermalColor(health.thermalStatus)
    val compact = buildAnnotatedString {
        withStyle(SpanStyle(color = alertColor(health, HealthKind.BATTERY))) {
            val percent = if (health.batteryPercent >= 0) health.batteryPercent.toString() else NO_DATA
            append(stringResource(if (health.charging) R.string.health_battery_charging_short else R.string.health_battery_short, percent))
        }
        append(SEPARATOR)
        withStyle(SpanStyle(color = heatColor)) {
            append(
                health.batteryTempTenthsC?.let { stringResource(R.string.health_temperature, formatTenths(it)) }
                    ?: stringResource(thermalLabel(health.thermalStatus))
            )
        }
        append(SEPARATOR)
        withStyle(SpanStyle(color = alertColor(health, HealthKind.STORAGE))) {
            append(health.storageMinutesLeft?.let { durationText(res, it) } ?: NO_DATA)
        }
    }
    Column(
        modifier
            .clip(CameraShapes.surface)
            .background(CameraTheme.colors.scrim)
            .border(CameraTheme.hairlineBorder, CameraShapes.surface)
            .heightIn(min = CameraDimens.touch)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.health_details)) { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            health.thermalHeadroom?.let { HeadroomBar(it, heatColor, stringResource(R.string.health_headroom, (it * PERCENT).toInt())) }
            Text(compact, color = OK_WHITE, style = CameraTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            val battery = if (health.charging) {
                stringResource(R.string.health_battery_charging)
            } else {
                health.batteryMinutesLeft?.let { durationText(res, it.toLong()) } ?: NO_DATA
            }
            val details = listOfNotNull(
                stringResource(R.string.health_detail_battery, battery),
                health.thermalHeadroom?.let { stringResource(R.string.health_headroom, (it * PERCENT).toInt()) },
                health.freeBytes?.let { stringResource(R.string.health_detail_free, formatGb(it)) }
            )
            Text(details.joinToString(SEPARATOR), color = OK_WHITE, style = CameraTheme.type.label)
        }
    }
}

/** Thermal headroom 0..1 (1 = SEVERE) as a small horizontal bar. */
@Composable
private fun HeadroomBar(headroom: Float, color: Color, description: String) {
    Canvas(Modifier.size(width = 24.dp, height = 6.dp).semantics { contentDescription = description }) {
        drawRect(Color(0x33FFFFFF))
        drawRect(color, size = Size(size.width * headroom.coerceIn(0f, 1f), size.height))
    }
}

private fun alertColor(health: HealthState, kind: HealthKind): Color = when (health.alerts.firstOrNull { it.kind == kind }?.level) {
    AlertLevel.CRITICAL -> ALERT_RED
    AlertLevel.WARNING -> WARN_YELLOW
    null -> OK_WHITE
}

/** `~45 min`, or `~2 h 05 min` from one hour on. */
internal fun durationText(res: Resources, minutes: Long): String = if (minutes < MINUTES_PER_HOUR) {
    res.getString(R.string.health_minutes, minutes)
} else {
    res.getString(R.string.health_hours, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
}

/** Gigabytes (10⁹ bytes, as file managers show them) with one decimal. */
internal fun formatGb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / BYTES_PER_GB)

private const val NO_DATA = "—"
private const val SEPARATOR = "  ·  "
private const val MINUTES_PER_HOUR = 60L
private const val BYTES_PER_GB = 1_000_000_000.0
private const val PERCENT = 100
