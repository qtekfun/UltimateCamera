package com.qtekfun.ultimatecamera.feature.camera.remote

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.feature.camera.CameraUiState
import com.qtekfun.ultimatecamera.feature.camera.R
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraDimens
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip
import com.qtekfun.ultimatecamera.feature.camera.ui.RecDot
import com.qtekfun.ultimatecamera.feature.camera.ui.formatTimecode

/**
 * The camera role on the camera screen (D56): a chip with the connected monitors that dims the screen, and the slave
 * mode itself (REQ-NET-008), drawn over everything: black, brightness at its lowest, only REC and the take time, every
 * touch swallowed; a long press wakes it. Recording is not touched. Nothing is shown while the role is off.
 */
@Composable
fun CameraRoleLayer(binding: CameraRoleBinding, state: CameraUiState, chipTop: Dp, chipEnd: Dp) {
    val ui by binding.ui.collectAsStateWithLifecycle()
    val slave by binding.slave.collectAsStateWithLifecycle()
    if (!ui.active) return
    Box(Modifier.fillMaxSize()) {
        if (slave) {
            SlaveScreen(state, onWake = { binding.setSlave(false) })
        } else {
            val label = pluralStringResource(R.plurals.role_monitors, ui.monitors, ui.monitors)
            QuickChip(
                label = label,
                onClick = { binding.setSlave(true) },
                icon = R.drawable.ic_cam_monitor,
                active = ui.streaming,
                contentDescription = stringResource(R.string.role_dim_description, label),
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = chipTop, end = chipEnd)
            )
        }
    }
}

@Composable
private fun SlaveScreen(state: CameraUiState, onWake: () -> Unit) {
    DimBrightness()
    val recording = state.camera as? CameraState.Recording
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onWake() }) }
    ) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CameraDimens.gap)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
                RecDot(recording = recording != null)
                Text(
                    if (recording != null) formatTimecode(state.elapsedMs, recording.profile.fps) else stringResource(R.string.role_standby),
                    style = CameraTheme.type.value,
                    color = if (recording != null) CameraTheme.colors.alertText else CameraTheme.colors.contentMuted
                )
            }
            Text(stringResource(R.string.role_wake_hint), style = CameraTheme.type.label, color = CameraTheme.colors.contentMuted)
        }
    }
}

/** Lowest backlight while shown; the previous level comes back when it goes. */
@Composable
private fun DimBrightness() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = DIM_LEVEL }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = previous } }
    }
}

/** Not 0: some phones turn the panel off at 0, and the camera must stay in the foreground. */
private const val DIM_LEVEL = 0.01f
