package com.qtekfun.ultimatecamera.feature.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.label
import com.qtekfun.ultimatecamera.core.recording.RestylePlan
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip

/** "Last photo" (photo mode, after the first shot of the session): opens the viewer with its restyle. */
@Composable
internal fun LastPhotoChip(viewModel: CameraViewModel) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    val last by viewModel.photo.lastPhoto.collectAsStateWithLifecycle()
    val restyle by viewModel.restyle.state.collectAsStateWithLifecycle()
    val photo = last
    if (ui.mode != CaptureMode.PHOTO || photo == null) return
    val description = stringResource(R.string.restyle_open_description)
    QuickChip(
        stringResource(R.string.restyle_open),
        onClick = { viewModel.restyle.open(photo) },
        icon = R.drawable.ic_cam_photo,
        active = restyle.photo != null,
        contentDescription = description
    )
    if (restyle.photo != null) LastPhotoViewer(restyle, viewModel.restyle)
}

/** The full-screen viewer: the photo (with the tuned style), why it cannot be restyled if so, the tuner and Save. */
@Composable
private fun LastPhotoViewer(ui: RestyleUi<Bitmap>, restyle: PhotoRestyle<Bitmap>) {
    Dialog(onDismissRequest = restyle::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.restyle_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = restyle::close) { Text(stringResource(R.string.restyle_close)) }
                }
                ui.photo?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                PreviewImage(ui)
                if (!ui.loading) RestyleBody(ui, restyle)
            }
        }
    }
}

@Composable
private fun PreviewImage(ui: RestyleUi<Bitmap>) {
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp), contentAlignment = Alignment.Center) {
        val bitmap = ui.preview
        if (bitmap == null) {
            if (ui.loading) CircularProgressIndicator()
        } else {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                image,
                contentDescription = stringResource(R.string.restyle_preview_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    if (ui.loading) Text(stringResource(R.string.restyle_loading), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun RestyleBody(ui: RestyleUi<Bitmap>, restyle: PhotoRestyle<Bitmap>) {
    Text(stringResource(R.string.restyle_style_of, stringResource(ui.original.preset.label)), style = MaterialTheme.typography.bodyMedium)
    when (ui.plan) {
        RestylePlan.NeedsBase -> Note(stringResource(R.string.restyle_needs_base))
        RestylePlan.Unknown -> Note(stringResource(R.string.restyle_unknown))
        else -> Unit
    }
    if (ui.plan?.possible == true) {
        StylePresetRow(ui.style) { restyle.setStyle(StyleParams.of(it)) }
        StyleTuner(ui.style, enabled = !ui.saving, onChange = restyle::setStyle)
        Button(onClick = restyle::save, enabled = ui.canSave) {
            Text(stringResource(if (ui.saving) R.string.restyle_saving else R.string.restyle_save))
        }
    }
    ui.saved?.let { Text(stringResource(R.string.restyle_saved, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    if (ui.failed) Note(stringResource(R.string.restyle_failed), error = true)
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
