package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import java.util.Locale

/** The photo shutter in photo mode, [video] (the REC button) otherwise. */
@Composable
internal fun ShutterOr(viewModel: CameraViewModel, video: @Composable () -> Unit) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    if (ui.mode == CaptureMode.PHOTO) {
        val camera by viewModel.photo.camera.collectAsStateWithLifecycle()
        ShutterButton(camera, onShoot = { viewModel.takePhoto() })
    } else {
        video()
    }
}

/** The photo status chip in photo mode, [video] (the recording status line) otherwise. */
@Composable
internal fun PhotoStatusOr(viewModel: CameraViewModel, video: @Composable () -> Unit) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    if (ui.mode == CaptureMode.PHOTO) {
        val camera by viewModel.photo.camera.collectAsStateWithLifecycle()
        PhotoStatus(camera)
    } else {
        video()
    }
}

/** [content] (video-only controls such as the codec and profile pickers) only in video mode. */
@Composable
internal fun VideoOnly(viewModel: CameraViewModel, content: @Composable () -> Unit) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    if (ui.mode == CaptureMode.VIDEO) content()
}

/** A short white flash over the preview on every shutter press. */
@Composable
internal fun ShutterFlash(viewModel: CameraViewModel) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    ShutterFlash(ui.shots)
}

/** Video / Photo switch (decision D41). Disabled while a take is recording. */
@Composable
internal fun CaptureModeSwitch(viewModel: CameraViewModel, enabled: Boolean) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    val modes = CaptureMode.entries
    SingleChoiceSegmentedButtonRow {
        modes.forEachIndexed { index, item ->
            SegmentedButton(
                selected = item == ui.mode,
                onClick = { viewModel.setCaptureMode(item) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                icon = {}
            ) {
                Text(stringResource(if (item == CaptureMode.VIDEO) R.string.camera_mode_video else R.string.camera_mode_photo))
            }
        }
    }
}

/** The photo shutter: a white disc in a ring, greyed while the camera is not ready or a photo is being taken. */
@Composable
internal fun ShutterButton(camera: PhotoState, onShoot: () -> Unit) {
    val ready = camera is PhotoState.Ready && !camera.capturing
    val description = stringResource(R.string.camera_take_photo)
    Canvas(
        Modifier
            .size(76.dp)
            .semantics { contentDescription = description }
            .clickable(enabled = ready, role = Role.Button, onClick = onShoot)
    ) {
        drawCircle(Color.White, radius = size.minDimension / 2, style = Stroke(width = 4.dp.toPx()))
        drawCircle(if (ready) Color.White else Color.Gray, radius = size.minDimension * 0.38f)
    }
}

/** The photo mode's status chip: JPEG size in megapixels, or the camera's state. */
@Composable
internal fun PhotoStatus(camera: PhotoState, modifier: Modifier = Modifier) {
    val text = when (camera) {
        is PhotoState.Ready -> stringResource(R.string.camera_photo_size, megapixels(camera.sizes.jpeg.area))
        is PhotoState.Failed -> stringResource(R.string.camera_failed, camera.message)
        PhotoState.Closed -> stringResource(R.string.camera_opening)
    }
    Text(
        text,
        color = if (camera is PhotoState.Failed) Color(0xFFE53935) else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** A short white flash over the preview each time [shots] grows: the shutter feedback. */
@Composable
private fun ShutterFlash(shots: Int) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(shots) {
        if (shots == 0) return@LaunchedEffect
        alpha.snapTo(FLASH_ALPHA)
        alpha.animateTo(0f, tween(FLASH_MS))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.White))
}

internal fun megapixels(pixels: Long): String = String.format(Locale.getDefault(), "%.1f", pixels / 1_000_000.0)

private const val FLASH_ALPHA = 0.8f
private const val FLASH_MS = 180
