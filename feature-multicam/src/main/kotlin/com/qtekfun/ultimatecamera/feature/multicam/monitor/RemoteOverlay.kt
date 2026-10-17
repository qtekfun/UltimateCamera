package com.qtekfun.ultimatecamera.feature.multicam.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.core.net.control.ControlStrings
import com.qtekfun.ultimatecamera.feature.camera.HealthBanner
import com.qtekfun.ultimatecamera.feature.camera.R as CamR
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraShapes
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.ScrimSurface
import com.qtekfun.ultimatecamera.feature.camera.ui.StatusReadout
import com.qtekfun.ultimatecamera.feature.camera.ui.StatusStrip
import com.qtekfun.ultimatecamera.feature.camera.ui.Tone
import com.qtekfun.ultimatecamera.feature.multicam.R

/** The camera's status strip, mirrored: REC + timecode, profile and codec badges, storage, battery, °C, dropped frames. */
@Composable
fun RemoteStatusStrip(ui: RemoteCameraUi, modifier: Modifier = Modifier, compact: Boolean = false) {
    StatusStrip(
        recording = ui.rec == RecUi.RECORDING,
        timecode = ui.timecode,
        modifier = modifier,
        badges = if (compact) emptyList() else listOfNotNull(ui.profileLabel, ui.codec)
    ) {
        ui.storageMinutes?.let {
            StatusReadout(
                CamR.drawable.ic_cam_storage,
                stringResource(R.string.mc_mon_storage_minutes, it),
                tone = ui.storageTone,
                contentDescription = stringResource(R.string.mc_mon_cd_storage)
            )
        }
        ui.batteryPercent?.let {
            StatusReadout(
                CamR.drawable.ic_cam_battery,
                stringResource(if (ui.charging) R.string.mc_mon_battery_charging else R.string.mc_mon_battery, it),
                tone = ui.batteryTone,
                contentDescription = stringResource(R.string.mc_mon_cd_battery)
            )
        }
        ui.temperatureC?.let {
            StatusReadout(
                CamR.drawable.ic_cam_thermal,
                stringResource(R.string.mc_mon_temperature, it),
                tone = ui.temperatureTone,
                contentDescription = stringResource(R.string.mc_mon_cd_temperature)
            )
        }
        if (!compact) {
            ui.droppedFrames?.let {
                StatusReadout(
                    CamR.drawable.ic_cam_info,
                    stringResource(R.string.mc_mon_dropped, it),
                    tone = ui.droppedTone,
                    contentDescription = stringResource(R.string.mc_mon_cd_dropped)
                )
            }
        }
    }
}

/** Link state pill (REQ-NET-007): camera name and a dot, green live, amber reconnecting or connecting, grey offline. */
@Composable
fun LinkPill(ui: RemoteCameraUi, modifier: Modifier = Modifier) {
    val colors = CameraTheme.colors
    val (dot, text) = when (ui.link) {
        LinkUi.LIVE -> colors.ok to R.string.mc_mon_link_live
        LinkUi.CONNECTING -> colors.warning to R.string.mc_mon_link_connecting
        LinkUi.RETRYING -> colors.warning to R.string.mc_mon_link_retrying
        LinkUi.OFFLINE -> colors.contentDisabled to R.string.mc_mon_link_offline
    }
    ScrimSurface(modifier, shape = CameraShapes.pill) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(ui.name, style = CameraTheme.type.value, color = colors.content, maxLines = 1)
            Text(stringResource(text), style = CameraTheme.type.label, color = colors.contentMuted, maxLines = 1)
        }
    }
}

/**
 * The camera's health alerts in the camera's own banner (owner: they must pop on the monitor), then the warnings the
 * forecast does not cover, then the last command error translated (tap to dismiss).
 */
@Composable
fun RemoteAlerts(ui: RemoteCameraUi, onDismissError: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HealthBanner(ui.alerts, Modifier.fillMaxWidth())
        val notices = ui.notices.map { ControlStrings.of(it) } + listOfNotNull(R.string.mc_mon_rec_error.takeIf { ui.rec == RecUi.ERROR })
        notices.forEach { AlertLine(stringResource(it), critical = false) }
        ui.error?.let { AlertLine(stringResource(ControlStrings.of(it)), critical = true, onClick = onDismissError) }
    }
}

@Composable
private fun AlertLine(text: String, critical: Boolean, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        text,
        color = if (critical) Color.White else Color.Black,
        style = CameraTheme.type.value,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (critical) CRITICAL_BG else WARNING_BG)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = stringResource(R.string.mc_mon_dismiss), onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** Same colours as the camera's HealthBanner. */
private val WARNING_BG = Color(0xEEFFB300)
private val CRITICAL_BG = Color(0xEED32F2F)

/** Text for the REC transition, or null when the button says it all. */
fun recHint(rec: RecUi): Int? = when (rec) {
    RecUi.STARTING -> R.string.mc_mon_rec_starting
    RecUi.STOPPING -> R.string.mc_mon_rec_stopping
    RecUi.SCHEDULED -> R.string.mc_mon_rec_scheduled
    else -> null
}

/** Tone of the whole tile edge on the master: red recording, amber trouble. */
fun tileTone(ui: RemoteCameraUi): Tone = when {
    ui.hasCritical || ui.rec == RecUi.ERROR -> Tone.ALERT
    ui.alerts.isNotEmpty() || ui.link != LinkUi.LIVE -> Tone.WARNING
    else -> Tone.NORMAL
}
