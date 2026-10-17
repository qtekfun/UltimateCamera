package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraShapes
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.ModeToggle
import com.qtekfun.ultimatecamera.feature.camera.ui.RecordButton
import com.qtekfun.ultimatecamera.feature.camera.ui.ScrimSurface
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

/**
 * Video / Photo toggle (decision D41): one small icon button, so the space goes to image settings (owner, D49).
 * Disabled while a take is recording.
 */
@Composable
internal fun CaptureModeToggle(viewModel: CameraViewModel, enabled: Boolean) {
    val ui by viewModel.photo.state.collectAsStateWithLifecycle()
    val photo = ui.mode == CaptureMode.PHOTO
    ModeToggle(photo = photo, enabled = enabled, onToggle = { viewModel.setCaptureMode(if (photo) CaptureMode.VIDEO else CaptureMode.PHOTO) })
}

/** The photo shutter: the record button's white-disc variant, greyed while the camera is not ready or busy. */
@Composable
internal fun ShutterButton(camera: PhotoState, onShoot: () -> Unit) {
    val ready = camera is PhotoState.Ready && !camera.capturing
    RecordButton(recording = false, onToggle = onShoot, enabled = ready, photo = true)
}

/** The photo mode's status pill: mode badge and JPEG size in megapixels, or the camera's state. */
@Composable
internal fun PhotoStatus(camera: PhotoState, modifier: Modifier = Modifier) {
    val text = when (camera) {
        is PhotoState.Ready -> stringResource(R.string.camera_photo_size, megapixels(camera.sizes.jpeg.area))
        is PhotoState.Failed -> stringResource(R.string.camera_failed, camera.message)
        PhotoState.Closed -> stringResource(R.string.camera_opening)
    }
    ScrimSurface(modifier, shape = CameraShapes.pill) {
        Row(
            Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_cam_photo), contentDescription = null, tint = CameraTheme.colors.content, modifier = Modifier.size(16.dp))
            Text(
                text,
                color = if (camera is PhotoState.Failed) CameraTheme.colors.rec else CameraTheme.colors.content,
                style = CameraTheme.type.value
            )
        }
    }
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
