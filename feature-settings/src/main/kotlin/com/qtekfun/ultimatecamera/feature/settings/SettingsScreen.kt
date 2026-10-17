package com.qtekfun.ultimatecamera.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import com.qtekfun.ultimatecamera.core.settings.TimecodeMode

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
    val multicam = viewModel.multicamPrefs.collectAsStateWithLifecycle().value
    val photoHdr = viewModel.photoHdrDefault.collectAsStateWithLifecycle().value
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { SettingsTitle(stringResource(R.string.settings_title)) }

        captureModes?.let { prefs ->
            item {
                SettingsSection(stringResource(R.string.settings_section_camera), R.drawable.ic_set_auto) {
                    Text(
                        stringResource(R.string.settings_start_mode_title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    )
                    Column(Modifier.selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        StartMode.entries.forEach { mode ->
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
                    Text(
                        stringResource(R.string.settings_timecode_title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    )
                    Column(Modifier.selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        TimecodeMode.entries.forEach { mode ->
                            RadioRow(
                                label = stringResource(
                                    when (mode) {
                                        TimecodeMode.RECORD_RUN -> R.string.settings_timecode_record_run
                                        TimecodeMode.TIME_OF_DAY -> R.string.settings_timecode_time_of_day
                                    }
                                ),
                                selected = prefs.timecode == mode,
                                onSelect = { viewModel.setTimecodeMode(mode) }
                            )
                        }
                    }
                }
            }
        }
        photoHdr?.let { mode -> item { PhotoSettings(mode, viewModel::setPhotoHdrDefault) } }
        onOpenAutoConfig?.let { open ->
            item {
                SettingsSection(stringResource(R.string.settings_autoconfig_title), R.drawable.ic_set_auto) {
                    SettingsNavItem(
                        title = stringResource(R.string.settings_autoconfig_open),
                        description = stringResource(R.string.settings_autoconfig_description),
                        onClick = open
                    )
                }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_section_storage), R.drawable.ic_set_storage) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { RecordingLocationSettings(viewModel, settings.separateWav) }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_section_audio), R.drawable.ic_set_audio) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { AudioSettings(viewModel, settings) }
            }
        }
        multicam?.let { prefs ->
            item {
                SettingsSection(stringResource(R.string.settings_section_multicam), R.drawable.ic_set_features) {
                    MulticamSettings(
                        prefs,
                        viewModel::setMulticamPrefs,
                        viewModel.secondCameraPair.collectAsStateWithLifecycle().value,
                        viewModel::setSecondCameraPair
                    )
                }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_section_display), R.drawable.ic_set_display) {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_max_brightness_title),
                    description = stringResource(R.string.settings_max_brightness_description),
                    checked = settings.maxBrightness,
                    onCheckedChange = viewModel::setMaxBrightness
                )
            }
        }
        item {
            SettingsSection(stringResource(R.string.luts_title), R.drawable.ic_set_lut) {
                SettingsNavItem(title = stringResource(R.string.luts_open), description = stringResource(R.string.luts_entry_description), onClick = {
                    showLuts =
                        true
                })
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_section_controls), R.drawable.ic_set_features) {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_volume_keys_title),
                    description = stringResource(R.string.settings_volume_keys_description),
                    checked = settings.volumeKeysRecord,
                    onCheckedChange = viewModel::setVolumeKeysRecord
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_alert_vibration_title),
                    description = stringResource(R.string.settings_alert_vibration_description),
                    checked = settings.alertVibration,
                    onCheckedChange = viewModel::setAlertVibration
                )
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_section_language), R.drawable.ic_set_language) {
                SettingsNavItem(
                    title = stringResource(R.string.settings_language_open),
                    description = stringResource(R.string.settings_language_description),
                    onClick = {
                        // Android 13+ per-app language screen; the app's languages come from its generated locale config.
                        val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        runCatching { context.startActivity(intent) }
                    }
                )
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_section_features), R.drawable.ic_set_features) {
                SettingsNote(stringResource(R.string.settings_features_hint))
                FeatureGroup.entries.forEach { group ->
                    SettingsSwitchItem(
                        title = stringResource(group.titleRes),
                        description = stringResource(group.descriptionRes),
                        checked = settings.isEnabled(group),
                        onCheckedChange = { viewModel.setFeatureGroupEnabled(group, it) }
                    )
                }
            }
        }
        item {
            SettingsSection(stringResource(R.string.settings_reset), R.drawable.ic_set_reset) {
                SettingsNavItem(title = stringResource(R.string.settings_reset), description = null, onClick = { confirmReset = true })
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

/** A whole-row toggle: the row is the touch target (field use, gloves) and carries the on/off state for accessibility. */
@Composable
internal fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
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
internal fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
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
