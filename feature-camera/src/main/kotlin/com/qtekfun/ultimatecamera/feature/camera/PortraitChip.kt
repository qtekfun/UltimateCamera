package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
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
import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip
import java.util.Locale

/**
 * The Portrait quick chip of the photo mode (D73), shown only when Settings › Photo › Portrait is on and the camera has
 * a depth source ([PortraitUi.chipShown]). Its menu turns portrait on or off, sets the aperture (off, f/1.4…f/16) and
 * whether the depth map is saved; with the maker's portrait (extension) the result is the maker's, so no slider.
 */
@Composable
internal fun PortraitChip(viewModel: CameraViewModel) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    if (ui.mode != CaptureMode.PHOTO) return
    val camera by viewModel.photo.camera.collectAsStateWithLifecycle()
    if (!PortraitUi.chipShown(camera)) return
    val ready = camera as? PhotoState.Ready ?: return
    var open by remember { mutableStateOf(false) }
    val portrait = ui.portrait
    val source = ready.portrait ?: ready.portraitSources.first()
    val label = if (portrait.on &&
        ready.portrait != null
    ) {
        stringResource(R.string.photo_portrait_on, sourceName(source))
    } else {
        stringResource(R.string.photo_portrait_off)
    }
    Box {
        QuickChip(label, onClick = { open = true }, enabled = !ready.capturing, active = portrait.on || open, contentDescription = label)
        PortraitMenu(open, { open = false }, portrait, source, viewModel::setPortrait)
    }
}

@Composable
private fun PortraitMenu(open: Boolean, onDismiss: () -> Unit, portrait: PortraitUi, source: DepthSource, onChange: (PortraitUi) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp)) {
            SwitchRow(stringResource(R.string.photo_portrait_switch), portrait.on) { onChange(portrait.copy(on = it)) }
            if (portrait.on && source != DepthSource.VENDOR_BOKEH) {
                Text(apertureLabel(portrait.aperture))
                Slider(
                    value = portrait.aperture.toFloat(),
                    onValueChange = { onChange(portrait.copy(aperture = Math.round(it))) },
                    valueRange = 0f..Bokeh.STOPS.size.toFloat(),
                    steps = Bokeh.STOPS.size - 1
                )
                SwitchRow(stringResource(R.string.photo_portrait_depth_map), portrait.saveDepth) { onChange(portrait.copy(saveDepth = it)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun apertureLabel(position: Int): String = if (position <= 0) {
    stringResource(R.string.photo_portrait_aperture_off)
} else {
    stringResource(R.string.photo_portrait_aperture, String.format(Locale.ROOT, "%.1f", Bokeh.fNumber(position)).removeSuffix(".0"))
}

@Composable
private fun sourceName(source: DepthSource): String = stringResource(
    when (source) {
        DepthSource.DEPTH_CAMERA -> R.string.photo_portrait_source_depth
        DepthSource.VENDOR_BOKEH -> R.string.photo_portrait_source_maker
        DepthSource.STEREO -> R.string.photo_portrait_source_stereo
    }
)
