package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.label
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip
import kotlin.math.roundToInt

/** The photo style chip (D79), photo mode only: "Style · Vibrant", opening the presets, the pad and the sliders. */
@Composable
internal fun PhotoStyleChip(viewModel: CameraViewModel) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    if (ui.mode != CaptureMode.PHOTO) return
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.style_chip, stringResource(ui.style.preset.label))
    QuickChip(label, onClick = { open = true }, active = open, contentDescription = label)
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.style_done)) } },
        title = { Text(stringResource(R.string.style_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.style_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StylePresetRow(ui.style) { viewModel.photo.setStyle(StyleParams.of(it)) }
                StyleTuner(ui.style, enabled = true, onChange = viewModel.photo::setStyle)
            }
        }
    )
}

/** One chip per preset; picking one resets the pad and sliders to its defaults. */
@Composable
internal fun StylePresetRow(style: StyleParams, onPick: (StylePreset) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StylePreset.entries.forEach { preset ->
            FilterChip(selected = style.preset == preset, onClick = { onPick(preset) }, label = { Text(stringResource(preset.label)) })
        }
    }
}

/**
 * The pad (tone × warmth), the palette and intensity sliders, and a reset; shared by photo, the video looks and the
 * multicam monitor's style picker.
 */
@Composable
fun StyleTuner(style: StyleParams, enabled: Boolean, onChange: (StyleParams) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StylePad(style, enabled, onChange)
        LabelledSlider(stringResource(R.string.style_palette), style.saturation, -1f..1f, enabled) { onChange(style.copy(saturation = it)) }
        LabelledSlider(stringResource(R.string.style_intensity), style.intensity, 0f..1f, enabled) { onChange(style.copy(intensity = it)) }
        TextButton(onClick = { onChange(StyleParams.of(style.preset)) }, enabled = enabled) { Text(stringResource(R.string.style_reset)) }
    }
}

@Composable
private fun LabelledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 72.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled, modifier = Modifier.weight(1f))
    }
}

/**
 * The 2D pad: x is tone (left flatter, right punchier), y is warmth (top warmer, bottom cooler). Drag or tap; TalkBack
 * gets the values and four step actions.
 */
@Composable
private fun StylePad(style: StyleParams, enabled: Boolean, onChange: (StyleParams) -> Unit) {
    val current by rememberUpdatedState(style)
    val change by rememberUpdatedState(onChange)
    fun at(position: Offset, size: IntSize) {
        if (!enabled || size.width == 0 || size.height == 0) return
        val tone = (position.x / size.width * 2f - 1f).coerceIn(-1f, 1f)
        val warmth = (1f - position.y / size.height * 2f).coerceIn(-1f, 1f)
        change(current.copy(tone = tone, warmth = warmth))
    }
    val description = stringResource(R.string.style_pad_description, (style.tone * 100).roundToInt(), (style.warmth * 100).roundToInt())
    val actions = listOf(
        stringResource(R.string.style_pad_more_tone) to { s: StyleParams -> s.copy(tone = s.tone + STEP) },
        stringResource(R.string.style_pad_less_tone) to { s: StyleParams -> s.copy(tone = s.tone - STEP) },
        stringResource(R.string.style_pad_warmer) to { s: StyleParams -> s.copy(warmth = s.warmth + STEP) },
        stringResource(R.string.style_pad_cooler) to { s: StyleParams -> s.copy(warmth = s.warmth - STEP) }
    ).map { (name, step) ->
        CustomAccessibilityAction(name) {
            change(step(current))
            true
        }
    }
    val dot = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(12.dp))
            .semantics {
                contentDescription = description
                if (enabled) customActions = actions
            }
            .pointerInput(enabled) { detectTapGestures { at(it, size) } }
            .pointerInput(enabled) { detectDragGestures { change, _ -> at(change.position, size) } }
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.verticalGradient(listOf(WARM_TINT, Color.Transparent, COOL_TINT)))
            drawLine(grid, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height))
            drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
            val x = (style.tone + 1f) / 2f * size.width
            val y = (1f - style.warmth) / 2f * size.height
            drawCircle(dot, radius = 10.dp.toPx(), center = Offset(x, y))
        }
        Text(stringResource(R.string.style_warmth), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopCenter))
        Text(stringResource(R.string.style_tone), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

private const val STEP = 0.1f
private val WARM_TINT = Color(0x40FF9A3C)
private val COOL_TINT = Color(0x403C8CFF)
