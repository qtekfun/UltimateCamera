package com.qtekfun.ultimatecamera.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.capabilities.ProbeOutcome
import com.qtekfun.ultimatecamera.core.capabilities.StopReason
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraDimens
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraPanel
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.OptionChip
import com.qtekfun.ultimatecamera.feature.camera.ui.PanelSection
import com.qtekfun.ultimatecamera.feature.camera.ui.StatusBadge
import com.qtekfun.ultimatecamera.feature.camera.ui.Tone

/**
 * Auto-configuration (D42): offered at first start (skippable) and from Settings. Shows each probe with its verdict,
 * the elapsed time, why the run stopped early, and the best mode; [onDone] returns to the camera, which then uses the
 * measured modes. Drawn with the camera design system (ui/Components.kt).
 */
@Composable
fun AutoConfigScreen(viewModel: AutoConfigViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) viewModel.start() }
    val start = {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.start()
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    val finish = {
        viewModel.markOffered()
        onDone()
    }
    // The camera belongs to whoever is in front: leaving the screen or the app stops the run (results so far are kept).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.cancel() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }

    CameraTheme {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(CameraDimens.edge)) {
            CameraPanel(stringResource(R.string.autoconfig_title), onClose = if (state.phase == AutoConfigPhase.RUNNING) null else finish) {
                Column(Modifier.padding(end = 8.dp).weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.autoconfig_body), style = CameraTheme.type.body, color = CameraTheme.colors.contentMuted)
                    if (state.phase != AutoConfigPhase.IDLE) Progress(state)
                    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.rows) { row -> ProbeLine(row) }
                    }
                    Summary(state)
                    Buttons(state, start = start, cancel = viewModel::cancel, finish = finish)
                }
            }
        }
    }
}

@Composable
private fun Progress(state: AutoConfigUiState) {
    PanelSection(stringResource(R.string.autoconfig_progress, state.done, state.total, formatElapsed(state.elapsedMs))) {
        LinearProgressIndicator(
            progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = CameraTheme.colors.accent,
            trackColor = CameraTheme.colors.scrimRaised
        )
    }
}

@Composable
private fun ProbeLine(row: ProbeRow) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(row.label, style = CameraTheme.type.value, color = CameraTheme.colors.content)
            row.measuredFps?.let {
                val measured = stringResource(R.string.autoconfig_measured, it, row.droppedFrames ?: 0)
                Text(measured, style = CameraTheme.type.label, color = CameraTheme.colors.contentMuted)
            }
        }
        StatusBadge(stringResource(outcomeText(row.outcome)), tone = outcomeTone(row.outcome))
    }
}

@Composable
private fun Summary(state: AutoConfigUiState) {
    state.stopReason?.let { Text(stringResource(stopText(it)), style = CameraTheme.type.body, color = CameraTheme.colors.warning) }
    when (state.phase) {
        AutoConfigPhase.DONE -> Text(
            state.best?.let { stringResource(R.string.autoconfig_best, it.label) } ?: stringResource(R.string.autoconfig_none_held),
            style = CameraTheme.type.title,
            color = if (state.best != null) CameraTheme.colors.ok else CameraTheme.colors.warning
        )
        AutoConfigPhase.FAILED -> {
            val failed = stringResource(R.string.autoconfig_failed, state.error.orEmpty())
            Text(failed, style = CameraTheme.type.body, color = CameraTheme.colors.rec)
        }
        else -> Unit
    }
}

@Composable
private fun Buttons(state: AutoConfigUiState, start: () -> Unit, cancel: () -> Unit, finish: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        when (state.phase) {
            AutoConfigPhase.IDLE -> {
                OptionChip(stringResource(R.string.autoconfig_skip), selected = false, onClick = finish)
                OptionChip(stringResource(R.string.autoconfig_start), selected = true, onClick = start)
            }
            AutoConfigPhase.RUNNING -> OptionChip(stringResource(R.string.autoconfig_cancel), selected = false, onClick = cancel)
            AutoConfigPhase.DONE, AutoConfigPhase.FAILED -> {
                OptionChip(stringResource(R.string.autoconfig_again), selected = false, onClick = start)
                OptionChip(stringResource(R.string.autoconfig_apply), selected = true, onClick = finish)
            }
        }
    }
}

internal fun outcomeTone(outcome: ProbeOutcome): Tone = when {
    outcome.held -> Tone.OK
    outcome.definitive -> Tone.ALERT
    else -> Tone.WARNING
}

@StringRes
internal fun outcomeText(outcome: ProbeOutcome): Int = when (outcome) {
    ProbeOutcome.ACCEPTED_AND_HELD -> R.string.autoconfig_outcome_held
    ProbeOutcome.DROPS -> R.string.autoconfig_outcome_drops
    ProbeOutcome.LOW_FPS -> R.string.autoconfig_outcome_low_fps
    ProbeOutcome.SESSION_REJECTED -> R.string.autoconfig_outcome_rejected
    ProbeOutcome.CAMERA_ERROR -> R.string.autoconfig_outcome_camera_error
    ProbeOutcome.CRASHED -> R.string.autoconfig_outcome_crashed
    ProbeOutcome.TIMEOUT -> R.string.autoconfig_outcome_timeout
    ProbeOutcome.ENCODER_UNSUPPORTED -> R.string.autoconfig_outcome_encoder
    ProbeOutcome.NOT_ADVERTISED_SKIPPED -> R.string.autoconfig_outcome_not_advertised
    ProbeOutcome.IMPLIED_FAILURE -> R.string.autoconfig_outcome_implied_failure
    ProbeOutcome.IMPLIED_HELD -> R.string.autoconfig_outcome_implied_held
    ProbeOutcome.SKIPPED_THERMAL -> R.string.autoconfig_outcome_skipped_thermal
    ProbeOutcome.SKIPPED_BUDGET -> R.string.autoconfig_outcome_skipped_budget
}

@StringRes
internal fun stopText(reason: StopReason): Int = when (reason) {
    StopReason.BUDGET -> R.string.autoconfig_stop_budget
    StopReason.THERMAL -> R.string.autoconfig_stop_thermal
    StopReason.CAMERA_ERRORS -> R.string.autoconfig_stop_camera
}
