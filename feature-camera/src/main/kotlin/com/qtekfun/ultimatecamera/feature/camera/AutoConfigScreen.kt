package com.qtekfun.ultimatecamera.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.capabilities.ProbeOutcome
import com.qtekfun.ultimatecamera.core.capabilities.StopReason

/**
 * Auto-configuration (D42): offered at first start (skippable) and from Settings. Shows each probe with its verdict,
 * the elapsed time, why the run stopped early, and the best mode; [onDone] returns to the camera, which then uses the
 * measured modes.
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

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.autoconfig_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.autoconfig_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.phase != AutoConfigPhase.IDLE) {
            Text(stringResource(R.string.autoconfig_progress, state.done, state.total, formatElapsed(state.elapsedMs)), style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(state.rows) { _, row -> ProbeLine(row) }
        }
        Summary(state)
        Buttons(state, start = start, cancel = viewModel::cancel, finish = finish)
    }
}

@Composable
private fun ProbeLine(row: ProbeRow) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        val color = when {
            row.outcome.held -> MaterialTheme.colorScheme.primary
            row.outcome.definitive -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val measured = row.measuredFps?.let { stringResource(R.string.autoconfig_measured, it, row.droppedFrames ?: 0) }.orEmpty()
        Text(stringResource(outcomeText(row.outcome)) + measured, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun Summary(state: AutoConfigUiState) {
    state.stopReason?.let { Text(stringResource(stopText(it)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    when (state.phase) {
        AutoConfigPhase.DONE -> Text(
            state.best?.let { stringResource(R.string.autoconfig_best, it.label) } ?: stringResource(R.string.autoconfig_none_held),
            style = MaterialTheme.typography.titleMedium
        )
        AutoConfigPhase.FAILED -> Text(stringResource(R.string.autoconfig_failed, state.error.orEmpty()), color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

@Composable
private fun Buttons(state: AutoConfigUiState, start: () -> Unit, cancel: () -> Unit, finish: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        when (state.phase) {
            AutoConfigPhase.IDLE -> {
                OutlinedButton(onClick = finish) { Text(stringResource(R.string.autoconfig_skip)) }
                Button(onClick = start) { Text(stringResource(R.string.autoconfig_start)) }
            }
            AutoConfigPhase.RUNNING -> OutlinedButton(onClick = cancel) { Text(stringResource(R.string.autoconfig_cancel)) }
            AutoConfigPhase.DONE, AutoConfigPhase.FAILED -> {
                OutlinedButton(onClick = start) { Text(stringResource(R.string.autoconfig_again)) }
                Button(onClick = finish) { Text(stringResource(R.string.autoconfig_apply)) }
            }
        }
    }
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
