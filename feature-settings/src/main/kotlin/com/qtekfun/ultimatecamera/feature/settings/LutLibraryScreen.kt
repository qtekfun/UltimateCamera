package com.qtekfun.ultimatecamera.feature.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.luts.LutEntry
import com.qtekfun.ultimatecamera.core.luts.LutLibrary
import com.qtekfun.ultimatecamera.core.luts.catalogue.CatalogueLut
import com.qtekfun.ultimatecamera.core.luts.catalogue.LicenceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a text dialog is editing. */
private sealed interface Edit {
    data object NewFolder : Edit

    data class RenameFolder(val path: String) : Edit

    data class RenameLut(val entry: LutEntry) : Edit
}

/** What a delete confirmation is about. */
private sealed interface Removal {
    data class Folder(val path: String) : Removal

    data class Lut(val entry: LutEntry) : Removal
}

/**
 * The LUT library (REQ-COL-003): folders, import of `.cube`/`.3dl` files through the system picker, rename and delete,
 * and the makers' catalogue (REQ-COL-006/007), whose LUTs are downloaded from the maker only after a confirmation that
 * shows the maker, host, size, licence notes and terms.
 */
@Composable
fun LutLibraryScreen(viewModel: LutLibraryViewModel, onBack: () -> Unit) {
    BackHandler { if (viewModel.state.value.folder != LutLibrary.ROOT) viewModel.up() else onBack() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = viewModel.snapshot.collectAsStateWithLifecycle().value
    var edit by remember { mutableStateOf<Edit?>(null) }
    var removal by remember { mutableStateOf<Removal?>(null) }
    var confirmDownload by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) viewModel.import(uris) }
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.luts_back)) }
                    Text(stringResource(R.string.luts_title), style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                Text(
                    breadcrumb(state.folder).joinToString(" / ") { if (it == LutLibrary.ROOT) "" else it.substringAfterLast('/') }
                        .let { stringResource(R.string.luts_root) + it },
                    style = MaterialTheme.typography.titleSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    // .cube files have no registered MIME type on Android: any file, the library checks the extension.
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text(stringResource(R.string.luts_import)) }
                    OutlinedButton(onClick = { edit = Edit.NewFolder }, enabled = !state.busy) { Text(stringResource(R.string.luts_new_folder)) }
                    if (state.folder != LutLibrary.ROOT) TextButton(onClick = viewModel::up) { Text(stringResource(R.string.luts_up)) }
                }
                if (state.busy) LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            }
            if (snapshot != null) {
                val folders = snapshot.foldersIn(state.folder)
                val luts = snapshot.lutsIn(state.folder).sortedBy { it.title.lowercase() }
                if (folders.isEmpty() && luts.isEmpty()) {
                    item { Text(stringResource(R.string.luts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(folders, key = { "f:$it" }) { path ->
                    FolderRow(path, onOpen = { viewModel.open(path) }, onRename = { edit = Edit.RenameFolder(path) }, onDelete = {
                        removal =
                            Removal.Folder(path)
                    })
                }
                items(luts, key = { "l:${it.id}" }) { entry ->
                    LutRow(entry, viewModel, onRename = { edit = Edit.RenameLut(entry) }, onDelete = { removal = Removal.Lut(entry) })
                }
                item { CatalogueHeader() }
                val downloaded = downloadedCatalogueIds(snapshot.luts)
                items(viewModel.catalogue, key = { "c:${it.id}" }) { lut ->
                    CatalogueRow(lut, inLibrary = lut.id in downloaded, enabled = !state.busy, onDownload = { confirmDownload = lut.id })
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }

    edit?.let { current -> EditDialog(current, viewModel, onDone = { edit = null }) }
    removal?.let { current -> RemovalDialog(current, viewModel, onDone = { removal = null }) }
    confirmDownload?.let { id ->
        viewModel.catalogue.firstOrNull { it.id == id }?.let { lut ->
            DownloadDialog(lut, viewModel, onDone = { confirmDownload = null })
        }
    }
}

@Composable
private fun FolderRow(path: String, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 64.dp, height = 16.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.secondaryContainer))
        Text("  ${path.substringAfterLast('/')}/", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        ItemMenu(onRename, onDelete)
    }
}

@Composable
private fun LutRow(entry: LutEntry, viewModel: LutLibraryViewModel, onRename: () -> Unit, onDelete: () -> Unit) {
    val swatch by produceState<Bitmap?>(null, entry.id) {
        value = withContext(Dispatchers.IO) { viewModel.swatch(entry.id)?.let { BitmapFactory.decodeFile(it.path) } }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val modifier = Modifier.size(width = 64.dp, height = 16.dp).clip(RoundedCornerShape(3.dp))
        val image = swatch
        if (image == null) {
            Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        } else {
            val bitmap = remember(image) { image.asImageBitmap() }
            Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.luts_entry_detail, entry.format.format.extension, entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ItemMenu(onRename, onDelete)
    }
}

@Composable
private fun ItemMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.luts_more)
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) { Text("⋮") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.luts_rename)) }, onClick = {
                open = false
                onRename()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.luts_delete)) }, onClick = {
                open = false
                onDelete()
            })
        }
    }
}

@Composable
private fun CatalogueHeader() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(stringResource(R.string.luts_catalogue_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.luts_catalogue_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CatalogueRow(lut: CatalogueLut, inLibrary: Boolean, enabled: Boolean, onDownload: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${lut.maker} · ${lut.name}", style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.luts_catalogue_detail, lut.inputCurve, lut.output, lut.devices.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (inLibrary) {
            Text(stringResource(R.string.luts_in_library), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            OutlinedButton(onClick = onDownload, enabled = enabled) { Text(stringResource(R.string.luts_download)) }
        }
    }
}

@Composable
private fun DownloadDialog(lut: CatalogueLut, viewModel: LutLibraryViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val prompt = remember(lut.id) { viewModel.describe(lut) }
    val megabytes = "%.1f".format(prompt.bytes / BYTES_PER_MB)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.luts_download_title, prompt.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.luts_download_body, prompt.maker, prompt.host, megabytes))
                if (prompt.licence != LicenceState.REDISTRIBUTABLE) Text(stringResource(R.string.luts_download_terms_note))
                Text(lut.licenceNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(prompt.termsUrl))) }
                }) { Text(stringResource(R.string.luts_download_terms)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDone()
                viewModel.download(lut)
            }) { Text(stringResource(R.string.luts_download)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.settings_cancel)) } }
    )
}

@Composable
private fun EditDialog(edit: Edit, viewModel: LutLibraryViewModel, onDone: () -> Unit) {
    val initial = when (edit) {
        Edit.NewFolder -> ""
        is Edit.RenameFolder -> edit.path.substringAfterLast('/')
        is Edit.RenameLut -> edit.entry.title
    }
    var text by rememberSaveable(edit) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(if (edit == Edit.NewFolder) R.string.luts_new_folder else R.string.luts_rename)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                onDone()
                when (edit) {
                    Edit.NewFolder -> viewModel.createFolder(text)
                    is Edit.RenameFolder -> viewModel.renameFolder(edit.path, text)
                    is Edit.RenameLut -> viewModel.rename(edit.entry, text)
                }
            }) { Text(stringResource(R.string.luts_save)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.settings_cancel)) } }
    )
}

@Composable
private fun RemovalDialog(removal: Removal, viewModel: LutLibraryViewModel, onDone: () -> Unit) {
    val name = when (removal) {
        is Removal.Folder -> removal.path.substringAfterLast('/')
        is Removal.Lut -> removal.entry.title
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.luts_delete_title, name)) },
        text = { Text(stringResource(if (removal is Removal.Folder) R.string.luts_delete_folder_text else R.string.luts_delete_lut_text)) },
        confirmButton = {
            TextButton(onClick = {
                onDone()
                when (removal) {
                    is Removal.Folder -> viewModel.deleteFolder(removal.path)
                    is Removal.Lut -> viewModel.delete(removal.entry)
                }
            }) { Text(stringResource(R.string.luts_delete)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.settings_cancel)) } }
    )
}

@Composable
private fun messageText(message: LibraryMessage): String = when (message) {
    is LibraryMessage.Imported -> stringResource(if (message.alreadyPresent) R.string.luts_already_present else R.string.luts_imported, message.title)
    is LibraryMessage.Failed -> stringResource(
        when (message.problem) {
            LibraryProblem.UNSUPPORTED_FORMAT -> R.string.luts_error_format
            LibraryProblem.INVALID_LUT -> R.string.luts_error_invalid
            LibraryProblem.TOO_LARGE -> R.string.luts_error_too_large
            LibraryProblem.INVALID_NAME -> R.string.luts_error_name
            LibraryProblem.FOLDER_NOT_EMPTY -> R.string.luts_error_not_empty
            LibraryProblem.FOLDER_EXISTS -> R.string.luts_error_exists
            LibraryProblem.NOT_FOUND -> R.string.luts_error_not_found
            LibraryProblem.NETWORK -> R.string.luts_error_network
            LibraryProblem.CHECKSUM -> R.string.luts_error_checksum
            LibraryProblem.STORAGE -> R.string.luts_error_storage
        }
    )
}

private const val BYTES_PER_MB = 1_000_000.0

/** The settings row that opens the library. */
@Composable
internal fun LutLibraryEntry(onOpen: () -> Unit) {
    Text(
        stringResource(R.string.luts_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.luts_entry_description), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpen, modifier = Modifier.padding(start = 16.dp)) { Text(stringResource(R.string.luts_open)) }
    }
}
