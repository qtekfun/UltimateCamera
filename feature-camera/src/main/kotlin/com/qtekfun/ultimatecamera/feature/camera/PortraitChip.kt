package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip

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
    val ready = (camera as? PhotoState.Ready)?.takeIf { PortraitUi.chipShown(it) } ?: return
    var open by remember { mutableStateOf(false) }
    val portrait = ui.portrait
    val active = portrait.activeSource(ready)
    val label = when {
        active != null -> stringResource(R.string.photo_portrait_on, stringResource(PortraitUi.sourceName(active)))
        portrait.unavailable(ready) -> stringResource(R.string.photo_portrait_unavailable)
        else -> stringResource(R.string.photo_portrait_off)
    }
    Box {
        QuickChip(label, onClick = { open = true }, enabled = !ready.capturing, active = portrait.on || open, contentDescription = label)
        PortraitMenu(open, { open = false }, portrait, portrait.sliderShown(ready), viewModel::setPortrait)
    }
}

@Composable
private fun PortraitMenu(open: Boolean, onDismiss: () -> Unit, portrait: PortraitUi, slider: Boolean, onChange: (PortraitUi) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp)) {
            SwitchRow(stringResource(R.string.photo_portrait_switch), portrait.on) { onChange(portrait.copy(on = it)) }
            if (slider) {
                val stop = PortraitUi.apertureText(portrait.aperture)
                Text(stop?.let { stringResource(R.string.photo_portrait_aperture, it) } ?: stringResource(R.string.photo_portrait_aperture_off))
                Slider(
                    value = portrait.aperture.toFloat(),
                    onValueChange = { onChange(portrait.copy(aperture = Math.round(it))) },
                    valueRange = 0f..Bokeh.STOPS.size.toFloat(),
                    steps = Bokeh.STOPS.size - 1
                )
                SwitchRow(stringResource(R.string.photo_portrait_depth_map), portrait.saveDepth) { onChange(portrait.copy(saveDepth = it)) }
                Text(stringResource(R.string.photo_portrait_focus_hint))
            }
        }
    }
}

/**
 * Tap-to-focus of the portrait blur (D87): over the photo preview while our own blur runs, a tap makes the depth at
 * that point the focus (live preview and the next shots) and a ring marks it. Place it right after the preview's
 * touch layers so the chips stay on top.
 */
@Composable
internal fun PortraitFocusLayer(viewModel: CameraViewModel) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    if (ui.mode != CaptureMode.PHOTO) return
    val camera by viewModel.photo.camera.collectAsStateWithLifecycle()
    val ready = camera as? PhotoState.Ready ?: return
    if (!ui.portrait.tapFocuses(ready)) return
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(viewModel) { detectTapGestures { viewModel.photo.setPortraitFocus(it.x, it.y, size.width, size.height) } }
    ) {
        val at = ui.portrait.focus?.let { viewModel.photo.focusOnPreview(it, viewSize.width, viewSize.height) } ?: return@Box
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, radius = FOCUS_RING.toPx(), center = Offset(at.first, at.second), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private val FOCUS_RING = 36.dp

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
