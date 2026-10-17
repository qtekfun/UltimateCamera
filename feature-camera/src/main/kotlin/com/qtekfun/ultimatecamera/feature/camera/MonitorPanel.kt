package com.qtekfun.ultimatecamera.feature.camera

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.settings.DesqueezeFactor
import com.qtekfun.ultimatecamera.core.settings.FrameLines
import com.qtekfun.ultimatecamera.core.settings.MonitorPrefs
import com.qtekfun.ultimatecamera.core.settings.MonitorScope
import com.qtekfun.ultimatecamera.core.settings.PeakingColor

private val PANEL_SCRIM = Color(0xCC000000)

/**
 * The monitoring tools toggle and panel of the camera screen (REQ-MON-001): zebras, false colour, peaking, one scope,
 * frame lines, guides and desqueeze. Hidden while `FeatureGroup.METERS` is disabled. Choices are persisted and only
 * change the preview, never the recording.
 */
@Composable
fun MonitorPanel(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.monitor.collectAsStateWithLifecycle()
    if (!state.available) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.monitor_close else R.string.monitor_open)) }
        if (open) MonitorOptions(state.prefs, viewModel::setMonitorPrefs)
    }
}

@Composable
private fun MonitorOptions(prefs: MonitorPrefs, onChange: (MonitorPrefs) -> Unit) {
    Column(
        Modifier
            .widthIn(max = 460.dp)
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PANEL_SCRIM)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Section(R.string.monitor_exposure) {
            Chip(R.string.monitor_zebras, prefs.zebras) { onChange(prefs.copy(zebras = !prefs.zebras)) }
            Chip(R.string.monitor_skin_zebras, prefs.skinZebras) { onChange(prefs.copy(skinZebras = !prefs.skinZebras)) }
            Chip(R.string.monitor_false_color, prefs.falseColor) { onChange(prefs.copy(falseColor = !prefs.falseColor)) }
        }
        if (prefs.zebras) {
            Section(R.string.monitor_zebra_level) {
                MonitorPrefs.ZEBRA_LEVELS.forEach { level ->
                    TextChip("$level %", prefs.zebraLevel == level) { onChange(prefs.copy(zebraLevel = level)) }
                }
            }
        }
        Section(R.string.monitor_focus) {
            Chip(R.string.monitor_peaking, prefs.peaking) { onChange(prefs.copy(peaking = !prefs.peaking)) }
            if (prefs.peaking) {
                PeakingColor.entries.forEach { color ->
                    Chip(color.labelRes, prefs.peakingColor == color) { onChange(prefs.copy(peakingColor = color)) }
                }
            }
        }
        Section(R.string.monitor_scope) {
            MonitorScope.entries.forEach { scope ->
                Chip(scope.labelRes, prefs.scope == scope) { onChange(prefs.copy(scope = scope)) }
            }
        }
        Section(R.string.monitor_frame_lines) {
            FrameLines.entries.forEach { lines ->
                if (lines == FrameLines.NONE) {
                    Chip(R.string.monitor_none, prefs.frameLines == lines) { onChange(prefs.copy(frameLines = lines)) }
                } else {
                    TextChip(lines.key, prefs.frameLines == lines) { onChange(prefs.copy(frameLines = lines)) }
                }
            }
        }
        Section(R.string.monitor_guides) {
            Chip(R.string.monitor_thirds, prefs.thirds) { onChange(prefs.copy(thirds = !prefs.thirds)) }
            Chip(R.string.monitor_centre, prefs.centre) { onChange(prefs.copy(centre = !prefs.centre)) }
        }
        Section(R.string.monitor_desqueeze) {
            DesqueezeFactor.entries.forEach { factor ->
                if (factor == DesqueezeFactor.NONE) {
                    Chip(R.string.monitor_none, prefs.desqueeze == factor) { onChange(prefs.copy(desqueeze = factor)) }
                } else {
                    TextChip("${factor.key}×", prefs.desqueeze == factor) { onChange(prefs.copy(desqueeze = factor)) }
                }
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Text(stringResource(title), color = Color.White, style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
    }
}

@Composable
private fun Chip(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) = TextChip(stringResource(label), selected, onClick)

@Composable
private fun TextChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, color = if (selected) Color.Unspecified else Color.White) })
}

private val PeakingColor.labelRes: Int
    get() = when (this) {
        PeakingColor.RED -> R.string.monitor_color_red
        PeakingColor.GREEN -> R.string.monitor_color_green
        PeakingColor.BLUE -> R.string.monitor_color_blue
        PeakingColor.YELLOW -> R.string.monitor_color_yellow
        PeakingColor.WHITE -> R.string.monitor_color_white
    }

private val MonitorScope.labelRes: Int
    get() = when (this) {
        MonitorScope.NONE -> R.string.monitor_none
        MonitorScope.HISTOGRAM -> R.string.monitor_histogram
        MonitorScope.WAVEFORM -> R.string.monitor_waveform
        MonitorScope.VECTORSCOPE -> R.string.monitor_vectorscope
    }
