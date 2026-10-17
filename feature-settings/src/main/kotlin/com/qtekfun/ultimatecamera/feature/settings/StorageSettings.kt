package com.qtekfun.ultimatecamera.feature.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.storage.ActiveRecordingStreams
import com.qtekfun.ultimatecamera.core.storage.LocationCheck
import com.qtekfun.ultimatecamera.core.storage.LocationStatus
import com.qtekfun.ultimatecamera.core.storage.R as StorageR
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.RecordingStreams
import com.qtekfun.ultimatecamera.core.storage.RecordingTimeEstimate
import com.qtekfun.ultimatecamera.core.storage.SpeedVerdict
import com.qtekfun.ultimatecamera.core.storage.VolumeInfo

/** Settings › Recording location (REQ-STO-001): where takes go, folder pick, phone storage, write-speed test. */
@Composable
internal fun RecordingLocationSettings(viewModel: SettingsViewModel, separateWav: Boolean) {
    val check by viewModel.location.collectAsStateWithLifecycle()
    val problem by viewModel.locationProblem.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        viewModel.chooseFolder(uri?.toString())
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val current = check
        if (current == null) {
            Text(stringResource(R.string.settings_storage_checking), style = MaterialTheme.typography.bodyMedium)
        } else {
            LocationSummary(current, separateWav)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(null) }) { Text(stringResource(StorageR.string.storage_choose_folder)) }
            if (current?.chosen is RecordingLocation.Folder) {
                OutlinedButton(onClick = viewModel::useDefaultLocation) { Text(stringResource(StorageR.string.storage_use_default)) }
            }
        }
        SpeedTest(speed, enabled = current?.effective != null, onRun = viewModel::runSpeedTest)
    }

    problem?.let {
        AlertDialog(
            onDismissRequest = viewModel::consumeLocationProblem,
            text = { Text(stringResource(it.messageRes)) },
            confirmButton = { TextButton(onClick = viewModel::consumeLocationProblem) { Text(stringResource(R.string.settings_ok)) } }
        )
    }
}

@Composable
private fun LocationSummary(check: LocationCheck, separateWav: Boolean) {
    when (val chosen = check.chosen) {
        RecordingLocation.Default -> {
            Text(stringResource(StorageR.string.storage_location_default), style = MaterialTheme.typography.bodyLarge)
            if (separateWav) Secondary(stringResource(StorageR.string.storage_location_default_audio_note))
        }
        is RecordingLocation.Folder -> {
            val path = chosen.tree?.path.orEmpty().ifEmpty { stringResource(R.string.settings_storage_drive_root) }
            Text(path, style = MaterialTheme.typography.bodyLarge)
            (check.status as? LocationStatus.Ready)?.volume?.let { Secondary(volumeLabel(it)) }
        }
    }
    when {
        check.effective == null -> Warning(stringResource(StorageR.string.storage_nowhere_to_record))
        check.fellBack -> {
            (check.status as? LocationStatus.Unavailable)?.let { Warning(stringResource(it.problem.messageRes)) }
            Warning(stringResource(StorageR.string.storage_fell_back))
        }
    }
    (check.effectiveStatus as? LocationStatus.Ready)?.freeBytes?.let { free ->
        val streams = ActiveRecordingStreams.current.value ?: RecordingStreams.uhd30TenBit(separateWav)
        val minutes = RecordingTimeEstimate.remainingMinutes(free, streams)
        val size = Formatter.formatShortFileSize(LocalContext.current, free)
        Secondary(pluralStringResource(StorageR.plurals.storage_minutes_remaining, minutes.toInt(), size, minutes.toInt()))
    }
}

@Composable
private fun volumeLabel(volume: VolumeInfo): String {
    val kind = stringResource(volume.kind.labelRes)
    return volume.description?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.settings_storage_volume, kind, it) } ?: kind
}

@Composable
private fun SpeedTest(speed: SpeedTestUi, enabled: Boolean, onRun: () -> Unit) {
    when (speed) {
        is SpeedTestUi.Running -> {
            Text(stringResource(StorageR.string.storage_speed_testing), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { speed.progress }, modifier = Modifier.fillMaxWidth())
        }
        else -> OutlinedButton(onClick = onRun, enabled = enabled) { Text(stringResource(StorageR.string.storage_speed_test)) }
    }
    when (speed) {
        is SpeedTestUi.Done -> {
            Text(
                stringResource(StorageR.string.storage_speed_result, speed.megabytesPerSecond, speed.requiredMegabytesPerSecond),
                style = MaterialTheme.typography.bodyMedium
            )
            val text = stringResource(speed.verdict.messageRes)
            if (speed.verdict == SpeedVerdict.OK) Secondary(text) else Warning(text)
        }
        SpeedTestUi.NotEnoughSpace -> Warning(stringResource(StorageR.string.storage_speed_not_enough_space))
        SpeedTestUi.Failed -> Warning(stringResource(StorageR.string.storage_speed_failed))
        else -> Unit
    }
}

@Composable
internal fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
