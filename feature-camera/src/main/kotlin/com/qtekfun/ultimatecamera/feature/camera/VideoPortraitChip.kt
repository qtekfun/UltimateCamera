package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip

/**
 * The Blur quick chip of the video mode (D89), shown only when Settings › Recording › Background blur in video is on and
 * the camera has a depth source for video ([VideoPortraitUi.chipShown]). Its menu turns the blur on or off (reopens the
 * camera), sets the aperture and says why the blur cannot run in this mode. The tap point of the preview is the focus.
 * Not while recording: the take keeps the blur it started with (the governor may only lower it).
 */
@Composable
internal fun VideoPortraitChip(control: VideoPortraitControl, recording: Boolean) {
    val status by control.status.collectAsStateWithLifecycle()
    if (!VideoPortraitUi.chipShown(status)) return
    val ui by control.ui.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val label = when (ui.label(status)) {
        VideoPortraitUi.ChipLabel.OFF -> stringResource(R.string.video_portrait_off)
        VideoPortraitUi.ChipLabel.ON -> stringResource(R.string.video_portrait_on, stringResource(PortraitUi.sourceName(requireNotNull(status.source))))
        VideoPortraitUi.ChipLabel.LOWERED -> stringResource(R.string.video_portrait_lowered)
        VideoPortraitUi.ChipLabel.UNAVAILABLE -> stringResource(R.string.video_portrait_unavailable)
    }
    Box {
        QuickChip(label, onClick = { open = true }, enabled = !recording, active = ui.on || open, contentDescription = label)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.video_portrait_switch), Modifier.weight(1f))
                    Switch(checked = ui.on, onCheckedChange = { control.set(ui.copy(on = it)) })
                }
                status.block?.takeIf { ui.on }?.let { block ->
                    Text(stringResource(VideoPortraitUi.blockText(block)), color = MaterialTheme.colorScheme.error)
                }
                if (ui.on) {
                    val stop = PortraitUi.apertureText(ui.aperture)
                    Text(stop?.let { stringResource(R.string.photo_portrait_aperture, it) } ?: stringResource(R.string.photo_portrait_aperture_off))
                    Slider(
                        value = ui.aperture.toFloat(),
                        onValueChange = { control.set(ui.copy(aperture = Math.round(it))) },
                        valueRange = 0f..Bokeh.STOPS.size.toFloat(),
                        steps = Bokeh.STOPS.size - 1
                    )
                    Text(stringResource(R.string.video_portrait_hint))
                }
            }
        }
    }
}
