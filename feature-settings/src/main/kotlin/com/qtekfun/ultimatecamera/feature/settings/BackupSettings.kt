package com.qtekfun.ultimatecamera.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.backup.BackupSummary
import com.qtekfun.ultimatecamera.core.backup.RestoreReport
import com.qtekfun.ultimatecamera.core.capabilities.CapabilityBackup

/** Settings › Backup (REQ-BAK-001/002, D65): export and import, with the dialogs each step needs. */
@Composable
internal fun BackupSettings(viewModel: BackupViewModel) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    var exportDialog by remember { mutableStateOf(false) }
    // Kept only until the document is created; never saved across process death.
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var includeCapabilities by remember { mutableStateOf(true) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val password = exportPassword
        exportPassword = null
        if (uri != null) viewModel.export(uri, password, includeCapabilities) else password?.fill(' ')
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.inspect(it) } }

    SettingsSection(stringResource(R.string.backup_title), R.drawable.ic_set_storage) {
        SettingsNavItem(
            title = stringResource(R.string.backup_export),
            description = stringResource(R.string.backup_export_description),
            onClick = { exportDialog = true },
            enabled = state !is BackupUiState.Working
        )
        SettingsNavItem(
            title = stringResource(R.string.backup_import),
            description = stringResource(R.string.backup_import_description),
            onClick = { pick.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
            enabled = state !is BackupUiState.Working
        )
        if (state is BackupUiState.Working) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (exportDialog) {
        ExportDialog(
            includeCapabilities = includeCapabilities,
            onIncludeCapabilities = { includeCapabilities = it },
            onConfirm = { password ->
                exportDialog = false
                exportPassword = password
                create.launch(BackupViewModel.suggestedName())
            },
            onDismiss = { exportDialog = false }
        )
    }
    BackupStateDialog(state, viewModel)
}

@Composable
private fun BackupStateDialog(state: BackupUiState, viewModel: BackupViewModel) {
    when (state) {
        is BackupUiState.NeedPassword -> PasswordDialog(state.wrong, onConfirm = { viewModel.inspect(state.uri, it) }, onDismiss = viewModel::dismiss)
        is BackupUiState.Review -> ReviewDialog(state.summary, onRestore = { replace -> viewModel.restore(state.uri, replace) }, onDismiss = viewModel::dismiss)
        is BackupUiState.Exported -> InfoDialog(stringResource(R.string.backup_exported), viewModel::dismiss)
        is BackupUiState.Restored -> InfoDialog(restoredText(state.report), viewModel::dismiss)
        is BackupUiState.Failed -> InfoDialog(problemText(state.problem), viewModel::dismiss)
        BackupUiState.Idle, BackupUiState.Working -> Unit
    }
}

@Composable
private fun ExportDialog(includeCapabilities: Boolean, onIncludeCapabilities: (Boolean) -> Unit, onConfirm: (CharArray?) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val mismatch = password != repeat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_export_text), style = MaterialTheme.typography.bodyMedium)
                PasswordField(password, { password = it }, stringResource(R.string.backup_password_optional))
                if (password.isNotEmpty()) PasswordField(repeat, { repeat = it }, stringResource(R.string.backup_password_repeat))
                if (password.isNotEmpty() && mismatch && repeat.isNotEmpty()) {
                    Text(stringResource(R.string.backup_password_mismatch), color = MaterialTheme.colorScheme.error)
                }
                if (password.isNotEmpty()) {
                    Text(stringResource(R.string.backup_password_warning), style = MaterialTheme.typography.bodySmall)
                }
                CheckRow(stringResource(R.string.backup_include_capabilities), includeCapabilities, onIncludeCapabilities)
            }
        },
        confirmButton = {
            TextButton(enabled = password.isEmpty() || !mismatch, onClick = { onConfirm(password.takeIf { it.isNotEmpty() }?.toCharArray()) }) {
                Text(stringResource(R.string.backup_export_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }
    )
}

@Composable
private fun PasswordDialog(wrong: Boolean, onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wrong) Text(stringResource(R.string.backup_password_wrong), color = MaterialTheme.colorScheme.error)
                PasswordField(password, { password = it }, stringResource(R.string.backup_password))
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { onConfirm(password.toCharArray()) }) { Text(stringResource(R.string.backup_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }
    )
}

@Composable
private fun ReviewDialog(summary: BackupSummary, onRestore: (replace: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_review_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val m = summary.manifest
                Text(stringResource(R.string.backup_review_origin, m.createdAt.take(DATE_CHARS), m.device.manufacturer, m.device.model, m.appVersion))
                Text(stringResource(R.string.backup_review_settings, summary.settingsCount))
                Text(stringResource(R.string.backup_review_luts, summary.lutCount))
                if (summary.hasCapabilities) {
                    Text(stringResource(if (summary.capabilitiesApply) R.string.backup_review_caps_apply else R.string.backup_review_caps_skip))
                }
                if (summary.deviceSpecificKeys.isNotEmpty()) {
                    Text(stringResource(R.string.backup_review_device_specific), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.backup_review_pairing), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.backup_review_modes), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onRestore(false) }) { Text(stringResource(R.string.backup_merge)) }
                TextButton(onClick = { onRestore(true) }) { Text(stringResource(R.string.backup_replace)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }
    )
}

@Composable
private fun InfoDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_title)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_ok)) } }
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun restoredText(report: RestoreReport): String {
    val lines = mutableListOf(
        stringResource(R.string.backup_restored_settings, report.settingsRestored),
        stringResource(R.string.backup_restored_luts, report.lutCount)
    )
    when (report.capabilities) {
        CapabilityBackup.Outcome.RESTORED -> lines += stringResource(R.string.backup_review_caps_apply)
        CapabilityBackup.Outcome.OTHER_DEVICE, CapabilityBackup.Outcome.UNREADABLE -> lines += stringResource(R.string.backup_review_caps_skip)
        null -> Unit
    }
    if (report.skippedKeys.isNotEmpty()) lines += stringResource(R.string.backup_review_device_specific)
    lines += stringResource(R.string.backup_review_pairing)
    return lines.joinToString("\n")
}

@Composable
private fun problemText(problem: BackupProblem): String = stringResource(
    when (problem) {
        BackupProblem.NOT_A_BACKUP -> R.string.backup_error_not_backup
        BackupProblem.WRONG_PASSWORD -> R.string.backup_password_wrong
        BackupProblem.CORRUPT -> R.string.backup_error_corrupt
        BackupProblem.TOO_NEW -> R.string.backup_error_too_new
        BackupProblem.STORAGE -> R.string.backup_error_storage
    }
)

/** `2026-10-17` out of the manifest's ISO instant. */
private const val DATE_CHARS = 10
