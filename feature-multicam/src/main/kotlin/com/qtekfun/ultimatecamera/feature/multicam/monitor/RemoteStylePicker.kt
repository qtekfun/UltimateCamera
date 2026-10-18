package com.qtekfun.ultimatecamera.feature.multicam.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.label
import com.qtekfun.ultimatecamera.feature.camera.StyleTuner
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.OptionChip
import com.qtekfun.ultimatecamera.feature.multicam.R
import kotlinx.coroutines.delay

/**
 * The camera's styles from the monitor (D79): which look (recording or monitor), the presets the camera offers, and the
 * tuner for the chosen one. Preset taps are sent at once; the pad and sliders send once they rest for a moment, and the
 * tuner shows the user's values until the camera's status confirms them. Refusals come back as the usual translated
 * error line.
 */
@Composable
internal fun RemoteStylePicker(ui: RemoteCameraUi, actions: MonitorActions) {
    if (ui.styles.isEmpty()) {
        Note(stringResource(R.string.mc_mon_style_not_supported))
        return
    }
    var target by rememberSaveable { mutableStateOf(StyleTarget.RECORDING) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OptionChip(stringResource(R.string.mc_mon_style_recording), target == StyleTarget.RECORDING, { target = StyleTarget.RECORDING })
        OptionChip(stringResource(R.string.mc_mon_style_monitor), target == StyleTarget.MONITOR, { target = StyleTarget.MONITOR })
    }
    val current = RemoteStyles.current(ui, target)
    var draft by remember(target) { mutableStateOf<StyleParams?>(null) }
    LaunchedEffect(current) { draft = RemoteStyles.settle(draft, current) }
    LaunchedEffect(draft) {
        val pending = draft ?: return@LaunchedEffect
        delay(SEND_AFTER_MS)
        actions.send(RemoteStyles.command(target, pending))
    }
    val panel = RemoteStyles.panel(ui, target, draft)
    val none = stringResource(if (target == StyleTarget.RECORDING) R.string.mc_mon_style_none else R.string.mc_mon_auto)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        panel.chips.forEach { chip ->
            val label = if (chip.none) none else chip.preset?.let { stringResource(it.label) } ?: chip.cameraLabel.orEmpty()
            OptionChip(label, chip.selected, {
                draft = null
                actions.send(chip.command)
            })
        }
    }
    if (panel.nextTake) Note(stringResource(R.string.mc_mon_style_next_take))
    panel.shown?.let { shown -> StyleTuner(shown, enabled = ui.controlsEnabled) { draft = it } }
    if (panel.notTunable) Note(stringResource(R.string.mc_mon_style_not_tunable))
}

@Composable
private fun Note(text: String) {
    Text(text, style = CameraTheme.type.body, color = CameraTheme.colors.contentMuted)
}

private const val SEND_AFTER_MS = 250L
