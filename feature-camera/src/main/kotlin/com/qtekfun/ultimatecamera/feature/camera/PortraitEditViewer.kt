package com.qtekfun.ultimatecamera.feature.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh

/**
 * The portrait tab of the last-photo viewer (D88): the photo rendered with the chosen aperture and focus (a tap on it
 * moves the focus there, a ring marks it), the aperture slider and *Save as a new photo*.
 */
@Composable
internal fun PortraitEditBody(ui: PortraitEditUi<Bitmap>, edit: PortraitEdit<Bitmap>) {
    EditPreview(ui, edit)
    val stop = PortraitUi.apertureText(ui.aperture)
    Text(stop?.let { stringResource(R.string.photo_portrait_aperture, it) } ?: stringResource(R.string.photo_portrait_aperture_off))
    Slider(
        value = ui.aperture.toFloat(),
        onValueChange = { edit.setAperture(Math.round(it)) },
        valueRange = 0f..Bokeh.STOPS.size.toFloat(),
        steps = Bokeh.STOPS.size - 1,
        enabled = !ui.saving
    )
    Text(stringResource(R.string.portrait_edit_hint), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = edit::save, enabled = ui.canSave) {
        Text(stringResource(if (ui.saving) R.string.restyle_saving else R.string.restyle_save))
    }
    ui.saved?.let { Text(stringResource(R.string.restyle_saved, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    if (ui.failed) Text(stringResource(R.string.portrait_edit_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun EditPreview(ui: PortraitEditUi<Bitmap>, edit: PortraitEdit<Bitmap>) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = ui.preview
    Box(
        Modifier.fillMaxWidth().height(PREVIEW_HEIGHT).onSizeChanged { box = it }.pointerInput(bitmap) {
            detectTapGestures { tap ->
                val image = bitmap ?: return@detectTapGestures
                PortraitEditUi.tapOnFitted(tap.x, tap.y, size.width.toFloat(), size.height.toFloat(), image.width, image.height)
                    ?.let { (u, v) -> edit.tapUpright(u, v) }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            CircularProgressIndicator()
        } else {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                image,
                contentDescription = stringResource(R.string.portrait_edit_preview_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            val (u, v) = ui.focusUpright
            PortraitEditUi.onFitted(u, v, box.width.toFloat(), box.height.toFloat(), bitmap.width, bitmap.height)?.let { (x, y) ->
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, radius = RING.toPx(), center = Offset(x, y), style = Stroke(width = 2.dp.toPx()))
                }
            }
            if (ui.rendering) CircularProgressIndicator()
        }
    }
}

private val PREVIEW_HEIGHT = 420.dp
private val RING = 28.dp
