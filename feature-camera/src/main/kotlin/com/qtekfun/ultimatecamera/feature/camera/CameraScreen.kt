package com.qtekfun.ultimatecamera.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.storage.FreeSpaceStatus

private val REC_RED = Color(0xFFE53935)
private val SCRIM = Color(0x99000000)

/** The main screen (Phase 2): full-screen preview, REC, timer, mode, storage line and audio meter. */
@Composable
fun CameraScreen(viewModel: CameraViewModel, onOpenSettings: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] == true || context.granted(Manifest.permission.CAMERA)
        viewModel.audioAllowed = context.granted(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) {
        viewModel.audioAllowed = context.granted(Manifest.permission.RECORD_AUDIO)
        if (!cameraGranted || !viewModel.audioAllowed) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    if (!cameraGranted) {
        PermissionNeeded(onRequest = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }, onOpenSettings)
        return
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    DisplayRotation(context, viewModel::setDisplayRotation)

    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.consumeMessage()
        }
    }

    // The UI follows the phone (owner: portrait and landscape); the controls move to the bottom in portrait.
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Preview(viewModel)
        ManualControlsLayer(viewModel.manualControls, state.profile.takeIf { state.camera !is CameraState.Closed }, if (portrait) PORTRAIT_BAR else 0.dp)
        Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusLine(state)
            StorageLine(state)
            if (portrait) state.audio?.let { AudioMeter(it, Modifier) }
        }
        if (!portrait) state.audio?.let { AudioMeter(it, Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(12.dp)) }
        if (portrait) {
            BottomControls(state, viewModel, onOpenSettings, onOpenDiagnostics, Modifier.align(Alignment.BottomCenter))
        } else {
            SideControls(state, viewModel, onOpenSettings, onOpenDiagnostics, Modifier.align(Alignment.CenterEnd))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
}

@Composable
private fun Preview(viewModel: CameraViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                // The camera screen stays on while it is visible: recordings run for a long time without touches.
                keepScreenOn = true
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        // Re-attached on every size change so the letterbox matches the new surface.
                        viewModel.setPreview(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = viewModel.setPreview(null)
                })
            }
        }
    )
}

/** Reports the display rotation now and on every change, including 180° flips (which are no configuration change). */
@Composable
private fun DisplayRotation(context: Context, onRotation: (Int) -> Unit) {
    DisposableEffect(context) {
        val displays = context.getSystemService(DisplayManager::class.java)
        fun report() {
            context.display?.rotation?.let { onRotation(it * QUARTER_TURN) }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) = report()
        }
        displays.registerDisplayListener(listener, null)
        report()
        onDispose { displays.unregisterDisplayListener(listener) }
    }
}

@Composable
private fun StatusLine(state: CameraUiState) {
    val recording = state.camera is CameraState.Recording
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(SCRIM).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (recording) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(REC_RED))
            Text(formatElapsed(state.elapsedMs), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Text(state.profile?.label.orEmpty(), color = Color.White, style = MaterialTheme.typography.labelLarge)
        when (val camera = state.camera) {
            is CameraState.Failed -> Text(stringResource(R.string.camera_failed, camera.message), color = REC_RED, style = MaterialTheme.typography.labelLarge)
            CameraState.Closed -> Text(stringResource(R.string.camera_opening), color = Color.White, style = MaterialTheme.typography.labelLarge)
            else -> Unit
        }
    }
}

@Composable
private fun StorageLine(state: CameraUiState) {
    val storage = state.storage ?: return
    val place = stringResource(if (storage.chosenIsFolder && !storage.fellBack) R.string.storage_folder else R.string.storage_default)
    val parts = buildList {
        add(place)
        storage.minutesLeft?.let { add(stringResource(R.string.storage_minutes_left, it)) }
        if (storage.fellBack) add(stringResource(R.string.storage_fell_back))
        if (storage.unavailable) add(stringResource(R.string.storage_unavailable))
        storage.space.messageRes?.let { add(stringResource(it)) }
    }
    val warn = storage.fellBack || storage.unavailable || storage.space != FreeSpaceStatus.OK
    Text(
        parts.joinToString(" · "),
        color = if (warn) Color(0xFFFFC107) else Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SCRIM).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun SideControls(state: CameraUiState, viewModel: CameraViewModel, onOpenSettings: () -> Unit, onOpenDiagnostics: () -> Unit, modifier: Modifier) {
    val recording = state.camera is CameraState.Recording
    Column(
        modifier.fillMaxHeight().background(SCRIM).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextButton(onClick = onOpenSettings, enabled = !recording) { Text(stringResource(R.string.camera_settings), color = Color.White) }
        RecButton(recording, enabled = recording || state.camera is CameraState.Previewing, onToggle = { viewModel.toggleRecording() })
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CodecPicker(state, enabled = !recording, onPick = viewModel::selectCodec)
            ProfilePicker(state, enabled = !recording, onPick = viewModel::selectProfile)
            TextButton(onClick = onOpenDiagnostics, enabled = !recording) { Text(stringResource(R.string.camera_diagnostics), color = Color.White) }
        }
    }
}

@Composable
private fun BottomControls(state: CameraUiState, viewModel: CameraViewModel, onOpenSettings: () -> Unit, onOpenDiagnostics: () -> Unit, modifier: Modifier) {
    val recording = state.camera is CameraState.Recording
    Column(
        modifier.fillMaxWidth().background(SCRIM).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodecPicker(state, enabled = !recording, onPick = viewModel::selectCodec)
            ProfilePicker(state, enabled = !recording, onPick = viewModel::selectProfile)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenSettings, enabled = !recording) { Text(stringResource(R.string.camera_settings), color = Color.White) }
            RecButton(recording, enabled = recording || state.camera is CameraState.Previewing, onToggle = { viewModel.toggleRecording() })
            TextButton(onClick = onOpenDiagnostics, enabled = !recording) { Text(stringResource(R.string.camera_diagnostics), color = Color.White) }
        }
    }
}

/** H.264 (widest compatibility, 8-bit), HEVC (default) or AV1 where the phone has an encoder. */
@Composable
private fun CodecPicker(state: CameraUiState, enabled: Boolean, onPick: (VideoCodec) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = enabled) { Text(codecName(state.codec)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            VideoCodec.entries.forEach { codec ->
                DropdownMenuItem(
                    text = { Text(codecName(codec)) },
                    onClick = {
                        open = false
                        onPick(codec)
                    }
                )
            }
        }
    }
}

@Composable
private fun codecName(codec: VideoCodec): String = stringResource(
    when (codec) {
        VideoCodec.H264 -> R.string.codec_h264
        VideoCodec.HEVC -> R.string.codec_hevc
        VideoCodec.AV1 -> R.string.codec_av1
    }
)

@Composable
private fun RecButton(recording: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val description = stringResource(if (recording) R.string.camera_stop else R.string.camera_record)
    Canvas(
        Modifier
            .size(76.dp)
            .semantics { contentDescription = description }
            .toggleable(value = recording, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
    ) {
        drawCircle(Color.White, radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()))
        val color = if (enabled) REC_RED else Color.Gray
        if (recording) {
            val side = size.minDimension * 0.4f
            val corner = Offset((size.width - side) / 2, (size.height - side) / 2)
            drawRoundRect(color, topLeft = corner, size = Size(side, side), cornerRadius = CornerRadius(6.dp.toPx()))
        } else {
            drawCircle(color, radius = size.minDimension * 0.38f)
        }
    }
}

@Composable
private fun ProfilePicker(state: CameraUiState, enabled: Boolean, onPick: (RecordingProfile) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = enabled) { Text(state.profile?.label ?: "—") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.profiles.forEach { profile ->
                val refused = profile in state.refused
                DropdownMenuItem(
                    text = { Text(if (refused) stringResource(R.string.camera_mode_refused, profile.label) else profile.label) },
                    onClick = {
                        open = false
                        onPick(profile)
                    }
                )
            }
        }
    }
}

/** Two horizontal peak bars (L, R), 0 dBFS at the right end; red when a channel clipped recently. */
@Composable
private fun AudioMeter(levels: AudioLevels, modifier: Modifier) {
    Column(modifier.width(180.dp).clip(RoundedCornerShape(6.dp)).background(SCRIM).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(levels.left, levels.right).forEach { level ->
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                drawRect(Color(0x33FFFFFF))
                val db = if (level <= 0f) MIN_DB else (20 * kotlin.math.log10(level)).coerceAtLeast(MIN_DB)
                val fraction = 1f - db / MIN_DB
                val color = when {
                    levels.clip -> REC_RED
                    db > -6f -> Color(0xFFFFC107)
                    else -> Color(0xFF43A047)
                }
                drawRect(color, size = Size(size.width * fraction, size.height))
            }
        }
    }
}

@Composable
private fun PermissionNeeded(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.camera_permission_body), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRequest) { Text(stringResource(R.string.camera_permission_grant)) }
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.camera_settings)) }
        }
    }
}

@Composable
private fun messageText(message: CameraMessage): String = when (message) {
    is CameraMessage.Saved -> stringResource(R.string.camera_saved, message.fileName)
    is CameraMessage.Problem -> stringResource(R.string.camera_problem, message.detail)
    is CameraMessage.FellDown -> stringResource(R.string.camera_fell_down, message.from, message.to)
    CameraMessage.NoStorage -> stringResource(R.string.camera_no_storage)
    is CameraMessage.CodecUnsupported -> stringResource(R.string.camera_codec_unsupported, codecName(message.codec))
}

/** `h:mm:ss` from one hour on, `mm:ss` before. */
internal fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private const val QUARTER_TURN = 90
private const val MIN_DB = -60f
