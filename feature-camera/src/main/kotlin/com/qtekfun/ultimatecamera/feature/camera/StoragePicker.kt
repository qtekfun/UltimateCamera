package com.qtekfun.ultimatecamera.feature.camera

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import com.qtekfun.ultimatecamera.core.storage.ActiveRecordingStreams
import com.qtekfun.ultimatecamera.core.storage.AndroidStorageBackend
import com.qtekfun.ultimatecamera.core.storage.LocationLabels
import com.qtekfun.ultimatecamera.core.storage.LocationMode
import com.qtekfun.ultimatecamera.core.storage.LocationOptions
import com.qtekfun.ultimatecamera.core.storage.LocationProblem
import com.qtekfun.ultimatecamera.core.storage.LocationStatus
import com.qtekfun.ultimatecamera.core.storage.R as StorageR
import com.qtekfun.ultimatecamera.core.storage.RecordingLocationRepository
import com.qtekfun.ultimatecamera.core.storage.RecordingStreams
import com.qtekfun.ultimatecamera.core.storage.RecordingTimeEstimate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The camera screen's location choice (D50): the options of [RecordingLocationRepository] for [StoragePicker], kept
 * fresh when drives come and go. [onChanged] lets the screen re-check its storage line; a choice never moves a take in
 * progress, it applies from the next one.
 */
class StorageChoice(
    private val scope: CoroutineScope,
    private val locations: RecordingLocationRepository,
    backend: AndroidStorageBackend,
    private val onChanged: suspend () -> Unit
) {
    private val _options = MutableStateFlow<LocationOptions?>(null)
    val options: StateFlow<LocationOptions?> = _options.asStateFlow()

    private val _problem = MutableStateFlow<LocationProblem?>(null)

    /** Why the last folder pick was refused; cleared by [consumeProblem]. */
    val problem: StateFlow<LocationProblem?> = _problem.asStateFlow()

    init {
        scope.launch {
            backend.volumeChanges().collect {
                _options.value = locations.options()
                onChanged()
            }
        }
    }

    fun refresh() {
        scope.launch { _options.value = locations.options() }
    }

    fun select(mode: LocationMode) {
        scope.launch {
            locations.select(mode)
            _options.value = locations.options()
            onChanged()
        }
    }

    /** The folder picker's result (null: cancelled). */
    fun chooseFolder(treeUri: String?) {
        if (treeUri == null) return
        scope.launch {
            val status = locations.choose(treeUri)
            if (status is LocationStatus.Unavailable) _problem.value = status.problem
            _options.value = locations.options()
            onChanged()
        }
    }

    fun consumeProblem() {
        _problem.value = null
    }
}

/**
 * Wraps the storage line ([anchor]): a tap opens a bottom sheet with Automatic (and where it records now), the phone,
 * each connected drive with its minutes left at the current profile, the picked folder and "Choose folder…".
 * Self-contained, so the screen only places it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoragePicker(choice: StorageChoice, recording: Boolean, modifier: Modifier = Modifier, anchor: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val problem by choice.problem.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> choice.chooseFolder(uri?.toString()) }

    Box(
        modifier.clickable {
            choice.refresh()
            open = true
        }
    ) { anchor() }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            val options by choice.options.collectAsStateWithLifecycle()
            options?.let {
                StorageOptions(it, recording, onSelect = { mode ->
                    choice.select(mode)
                    open = false
                }, onChooseFolder = {
                    open = false
                    picker.launch(null)
                })
            }
        }
    }

    problem?.let {
        AlertDialog(
            onDismissRequest = choice::consumeProblem,
            text = { Text(stringResource(it.messageRes)) },
            confirmButton = { TextButton(onClick = choice::consumeProblem) { Text(stringResource(android.R.string.ok)) } }
        )
    }
}

@Composable
private fun StorageOptions(options: LocationOptions, recording: Boolean, onSelect: (LocationMode) -> Unit, onChooseFolder: () -> Unit) {
    val resources = LocalContext.current.resources
    val streams = ActiveRecordingStreams.current.value ?: RecordingStreams.uhd30TenBit(separateWav = false)
    val minutes: (Long?) -> String? = { free ->
        free?.let { resources.getString(StorageR.string.storage_option_minutes, RecordingTimeEstimate.remainingMinutes(it, streams).toInt()) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(stringResource(StorageR.string.storage_picker_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
        if (recording) {
            Text(
                stringResource(StorageR.string.storage_picker_next_take),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(Modifier.selectableGroup()) {
            OptionRow(
                title = stringResource(StorageR.string.storage_mode_automatic),
                subtitle = stringResource(
                    StorageR.string.storage_mode_automatic_description,
                    LocationLabels.location(resources, options.automaticTarget, options.drives)
                ),
                selected = options.mode == LocationMode.Automatic
            ) { onSelect(LocationMode.Automatic) }
            OptionRow(stringResource(StorageR.string.storage_mode_phone), minutes(options.phoneFreeBytes), options.mode == LocationMode.Phone) {
                onSelect(LocationMode.Phone)
            }
            options.drives.forEach { drive ->
                val mode = LocationMode.Volume(drive.location.volumeName)
                OptionRow(LocationLabels.location(resources, drive.location, options.drives), minutes(drive.freeBytes), options.mode == mode) {
                    onSelect(mode)
                }
            }
            options.folder?.let { folder ->
                OptionRow(LocationLabels.location(resources, folder), null, options.mode == LocationMode.Folder) { onSelect(LocationMode.Folder) }
            }
        }
        // The system picker sends the app to the background, which ends a take (D40): not while recording.
        OutlinedButton(onClick = onChooseFolder, enabled = !recording, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(StorageR.string.storage_choose_folder_ellipsis))
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
