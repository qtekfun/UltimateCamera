package com.qtekfun.ultimatecamera.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.settings.FeatureGroup
import com.qtekfun.ultimatecamera.core.settings.StartMode

/**
 * App settings: recording location and write speed (REQ-STO-001), audio (REQ-AUD-001..005), display (maximum
 * brightness, REQ-MON-003), language (the system's per-app language screen) and the
 * feature groups that show or hide parts of the single-mode UI (docs/01, "Interfaz").
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenAutoConfig: (() -> Unit)? = null) {
    var showLuts by rememberSaveable { mutableStateOf(false) }
    if (showLuts) {
        LutLibraryScreen(androidx.lifecycle.viewmodel.compose.viewModel(), onBack = { showLuts = false })
        return
    }
    val settings = viewModel.settings.collectAsStateWithLifecycle().value ?: return
    val captureModes = viewModel.captureModePrefs.collectAsStateWithLifecycle().value
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp)) }

        captureModes?.let { prefs ->
            item { SectionTitle(R.string.settings_section_camera) }
            item {
                Text(stringResource(R.string.settings_start_mode_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            }
            items(StartMode.entries, key = { "start_" + it.key }) { mode ->
                RadioRow(
                    label = stringResource(
                        when (mode) {
                            StartMode.LAST_USED -> R.string.settings_start_mode_last
                            StartMode.VIDEO -> R.string.settings_start_mode_video
                            StartMode.PHOTO -> R.string.settings_start_mode_photo
                        }
                    ),
                    selected = prefs.startMode == mode,
                    onSelect = { viewModel.setStartMode(mode) }
                )
            }
        }
        item { SectionTitle(R.string.settings_section_storage) }
        item { RecordingLocationSettings(viewModel, settings.separateWav) }

        item { SectionTitle(R.string.settings_section_audio) }
        item { AudioSettings(viewModel, settings) }

        item { SectionTitle(R.string.settings_section_display) }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_max_brightness_title),
                description = stringResource(R.string.settings_max_brightness_description),
                checked = settings.maxBrightness,
                onCheckedChange = viewModel::setMaxBrightness
            )
        }

        if (onOpenAutoConfig != null) {
            item { SectionTitle(R.string.settings_section_camera) }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_autoconfig_description), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = onOpenAutoConfig) { Text(stringResource(R.string.settings_autoconfig_open)) }
                }
            }
        }

        item { LutLibraryEntry(onOpen = { showLuts = true }) }
        item { SectionTitle(R.string.settings_section_controls) }
        item {
            SwitchRow(
                title = stringResource(R.string.settings_volume_keys_title),
                description = stringResource(R.string.settings_volume_keys_description),
                checked = settings.volumeKeysRecord,
                onCheckedChange = viewModel::setVolumeKeysRecord
            )
        }

        item { SectionTitle(R.string.settings_section_language) }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_language_description), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(16.dp))
                OutlinedButton(onClick = {
                    // Android 13+ per-app language screen; the app's languages come from its generated locale config.
                    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    runCatching { context.startActivity(intent) }
                }) { Text(stringResource(R.string.settings_language_open)) }
            }
        }

        item { SectionTitle(R.string.settings_section_features) }
        item {
            Text(
                stringResource(R.string.settings_features_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(FeatureGroup.entries, key = { it.key }) { group ->
            SwitchRow(
                title = stringResource(group.titleRes),
                description = stringResource(group.descriptionRes),
                checked = settings.isEnabled(group),
                onCheckedChange = { viewModel.setFeatureGroupEnabled(group, it) }
            )
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.padding(bottom = 24.dp)) {
                Text(stringResource(R.string.settings_reset))
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetToDefaults()
                }) { Text(stringResource(R.string.settings_reset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.settings_cancel)) } }
        )
    }
}

@Composable
private fun SectionTitle(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

/** A whole-row toggle: the row is the touch target (field use, gloves) and carries the on/off state for accessibility. */
@Composable
internal fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One choice of a single-choice list; the whole row is the touch target. */
@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
