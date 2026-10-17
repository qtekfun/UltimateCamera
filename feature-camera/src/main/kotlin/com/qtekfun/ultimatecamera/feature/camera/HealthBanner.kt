package com.qtekfun.ultimatecamera.feature.camera

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val WARNING_BG = Color(0xEEFFB300)
private val CRITICAL_BG = Color(0xEED32F2F)

/**
 * Predictive health alerts over the preview (D52): one colour-coded line per risk, critical first, e.g. "Battery for
 * ~8 min" or "Overheating in ~3 min: switch to 4K30 or 1080p". It appears and hides by itself as the forecast changes.
 * Place it at the top of the screen with [modifier], away from REC.
 */
@Composable
fun HealthBanner(alerts: List<HealthAlert>, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = alerts.isNotEmpty(), modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (alert in alerts.sortedByDescending { it.level }) {
                val critical = alert.level == AlertLevel.CRITICAL
                Text(
                    alertText(alert),
                    color = if (critical) Color.White else Color.Black,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (critical) CRITICAL_BG else WARNING_BG)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}

@Composable
private fun alertText(alert: HealthAlert): String =
    if (alert.minutes <= 0) stringResource(nowText(alert.kind)) else stringResource(soonText(alert.kind), alert.minutes)

@StringRes
internal fun nowText(kind: HealthKind): Int = when (kind) {
    HealthKind.BATTERY -> R.string.health_alert_battery_now
    HealthKind.STORAGE -> R.string.health_alert_storage_now
    HealthKind.THERMAL -> R.string.health_alert_thermal_now
}

@StringRes
internal fun soonText(kind: HealthKind): Int = when (kind) {
    HealthKind.BATTERY -> R.string.health_alert_battery
    HealthKind.STORAGE -> R.string.health_alert_storage
    HealthKind.THERMAL -> R.string.health_alert_thermal
}
