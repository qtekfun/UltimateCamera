package com.qtekfun.ultimatecamera.feature.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.gpu.LogSource
import com.qtekfun.ultimatecamera.core.gpu.UcLog
import com.qtekfun.ultimatecamera.core.gpu.UcLogLuts
import com.qtekfun.ultimatecamera.core.gpu.style.StyleEngine
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.label
import com.qtekfun.ultimatecamera.core.luts.LutSwatch
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One row of the picker: what it selects, its title and subtitle, and how its swatch is made; [header] rows only label a section. */
private class LookOption(
    val key: String,
    val title: String,
    val subtitle: String?,
    val enabled: Boolean,
    val swatch: SwatchSource,
    val header: Boolean = false,
    val onPick: () -> Unit
)

private sealed interface SwatchSource {
    data object Plain : SwatchSource

    data object UcLogCurve : SwatchSource

    data object ViewingLut : SwatchSource

    data class Library(val id: String) : SwatchSource

    data class Style(val params: StyleParams) : SwatchSource
}

/**
 * The camera's LUT button and its dialog (REQ-COL-003/004): two separate choices, the look baked into the recording
 * (none, UC-Log on 10-bit, or a library LUT) and the look shown only on the monitor (automatic, none, the built-in UC-Log
 * → Rec.709, or a library LUT), each row with its swatch, then the generated styles (D79) with their pad and sliders when
 * one is chosen. The recording choice is locked during a take.
 */
@Composable
fun LutPicker(looks: CameraLooks, recording: Boolean) {
    val state by looks.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.lut_button)
    QuickChip(label, onClick = { open = true }, icon = R.drawable.ic_cam_lut, active = open, contentDescription = stringResource(R.string.ui_lut))
    if (!open) return
    var monitorTab by rememberSaveable { mutableStateOf(recording) }
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.lut_close)) } },
        title = { Text(stringResource(R.string.lut_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !monitorTab, onClick = { monitorTab = false }, label = { Text(stringResource(R.string.lut_tab_recording)) })
                    FilterChip(selected = monitorTab, onClick = { monitorTab = true }, label = { Text(stringResource(R.string.lut_tab_monitor)) })
                }
                val hint = when {
                    monitorTab -> R.string.lut_monitor_hint
                    recording -> R.string.lut_recording_locked
                    else -> R.string.lut_recording_hint
                }
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.missing) {
                    Text(
                        stringResource(R.string.lut_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                val options = if (monitorTab) monitorOptions(state, looks) else recordingOptions(state, looks, locked = recording)
                val chosenStyle = if (monitorTab) (state.monitor as? MonitorLook.Style)?.params else (state.recording as? RecordingLook.Style)?.params
                val selected =
                    chosenStyle?.let { StyleParams.of(it.preset).token } ?: if (monitorTab) state.monitor.token.orEmpty() else state.recording.token.orEmpty()
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(options, key = { it.key }) { option -> OptionRow(option, option.key == selected, looks) }
                    if (chosenStyle != null) {
                        item(key = "tuner") {
                            StyleTuner(chosenStyle, enabled = monitorTab || !recording) { tuned ->
                                if (monitorTab) looks.setMonitor(MonitorLook.Style(tuned)) else looks.setRecording(RecordingLook.Style(tuned))
                            }
                        }
                    }
                }
                if (state.library.isEmpty()) {
                    Text(
                        stringResource(R.string.lut_library_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

/** The "Styles" section: a header and one row per preset at its defaults ([pick] receives it). */
@Composable
private fun styleOptions(enabled: Boolean, pick: (StyleParams) -> Unit): List<LookOption> = buildList {
    add(LookOption("header:styles", stringResource(R.string.lut_styles_header), null, false, SwatchSource.Plain, header = true) {})
    val subtitle = stringResource(R.string.lut_style_subtitle)
    StylePreset.entries.filter { it != StylePreset.STANDARD }.forEach { preset ->
        val params = StyleParams.of(preset)
        add(LookOption(params.token, stringResource(preset.label), subtitle, enabled, SwatchSource.Style(params)) { pick(params) })
    }
}

@Composable
private fun recordingOptions(state: LooksUiState, looks: CameraLooks, locked: Boolean): List<LookOption> = buildList {
    val noneSubtitle = state.defaultStyle?.takeUnless {
        it.isIdentity
    }?.let { stringResource(R.string.lut_none_default_style, stringResource(it.preset.label)) }
    add(
        LookOption("", stringResource(R.string.lut_none), noneSubtitle ?: stringResource(R.string.lut_none_recording), !locked, SwatchSource.Plain) {
            looks.setRecording(RecordingLook.None)
        }
    )
    add(
        LookOption(
            RecordingLook.UcLog.token,
            UcLog.NAME,
            stringResource(if (state.tenBit) R.string.lut_uclog_subtitle else R.string.lut_uclog_needs_10bit),
            !locked && state.tenBit,
            SwatchSource.UcLogCurve
        ) { looks.setRecording(RecordingLook.UcLog) }
    )
    state.library.forEach { entry ->
        val look = RecordingLook.Library(entry.id)
        add(LookOption(look.token, entry.title, entry.folder.ifEmpty { null }, !locked, SwatchSource.Library(entry.id)) { looks.setRecording(look) })
    }
    addAll(styleOptions(!locked) { looks.setRecording(RecordingLook.Style(it)) })
}

@Composable
private fun monitorOptions(state: LooksUiState, looks: CameraLooks): List<LookOption> = buildList {
    add(
        LookOption("", stringResource(R.string.lut_auto), stringResource(R.string.lut_auto_subtitle), true, SwatchSource.Plain) {
            looks.setMonitor(MonitorLook.Auto)
        }
    )
    add(
        LookOption(MonitorLook.None.token, stringResource(R.string.lut_none), stringResource(R.string.lut_none_monitor), true, SwatchSource.Plain) {
            looks.setMonitor(MonitorLook.None)
        }
    )
    add(
        LookOption(MonitorLook.UcLogToRec709.token, stringResource(R.string.lut_viewing), stringResource(R.string.lut_builtin), true, SwatchSource.ViewingLut) {
            looks.setMonitor(MonitorLook.UcLogToRec709)
        }
    )
    state.library.forEach { entry ->
        val look = MonitorLook.Library(entry.id)
        add(LookOption(look.token, entry.title, entry.folder.ifEmpty { null }, true, SwatchSource.Library(entry.id)) { looks.setMonitor(look) })
    }
    addAll(styleOptions(true) { looks.setMonitor(MonitorLook.Style(it)) })
}

@Composable
private fun OptionRow(option: LookOption, selected: Boolean, looks: CameraLooks) {
    if (option.header) {
        Text(option.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = option.enabled, role = Role.RadioButton, onClick = option.onPick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = option.enabled)
        Swatch(option.swatch, looks)
        Column(Modifier.weight(1f)) {
            val alpha = if (option.enabled) 1f else DISABLED_ALPHA
            Text(option.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            option.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
    }
}

/** The LUT applied to a grey ramp over a hue sweep ([LutSwatch]); generated swatches are made once per process. */
@Composable
private fun Swatch(source: SwatchSource, looks: CameraLooks) {
    val bitmap by produceState<Bitmap?>(null, source) {
        value = when (source) {
            is SwatchSource.Library -> withContext(Dispatchers.IO) { looks.swatch(source.id)?.let { BitmapFactory.decodeFile(it.path) } }
            else -> withContext(Dispatchers.Default) { GeneratedSwatches.get(source) }
        }
    }
    val modifier = Modifier.size(width = 64.dp, height = 16.dp).clip(RoundedCornerShape(3.dp))
    val image = bitmap
    if (image == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    } else {
        val imageBitmap = remember(image) { image.asImageBitmap() }
        Image(imageBitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
    }
}

private object GeneratedSwatches {
    private val cache = HashMap<SwatchSource, Bitmap>()

    @Synchronized
    fun get(source: SwatchSource): Bitmap = cache.getOrPut(source) {
        val pixels = when (source) {
            SwatchSource.UcLogCurve -> UcLog.encodeArgb(LogSource.BT709, LutSwatch.input())
            SwatchSource.ViewingLut -> LutSwatch.pixels(UcLogLuts.toRec709())
            is SwatchSource.Style -> LutSwatch.pixels(StyleEngine.lutFor(source.params))
            else -> LutSwatch.input()
        }
        Bitmap.createBitmap(pixels, LutSwatch.WIDTH, LutSwatch.HEIGHT, Bitmap.Config.ARGB_8888)
    }
}

private const val DISABLED_ALPHA = 0.38f
