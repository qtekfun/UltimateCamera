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
import androidx.compose.foundation.layout.heightIn
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
import com.qtekfun.ultimatecamera.feature.camera.ui.AudioMeterBars
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraDimens
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraIconButton
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraShapes
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraSnackbarHost
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip
import com.qtekfun.ultimatecamera.feature.camera.ui.RecordButton
import com.qtekfun.ultimatecamera.feature.camera.ui.ScrimSurface
import com.qtekfun.ultimatecamera.feature.camera.ui.StatusBadge
import com.qtekfun.ultimatecamera.feature.camera.ui.StatusReadout
import com.qtekfun.ultimatecamera.feature.camera.ui.StatusStrip
import com.qtekfun.ultimatecamera.feature.camera.ui.Tone
import com.qtekfun.ultimatecamera.feature.camera.ui.formatTimecode

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
    val status by viewModel.status.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val volumeKeys by viewModel.volumeKeys.collectAsStateWithLifecycle()
    // Volume keys: REC in video mode, shutter in photo mode (D41, D47).
    VolumeKeyRecording(enabled = volumeKeys) { if (viewModel.photo.active) viewModel.takePhoto() else viewModel.toggleRecording() }
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.consumeMessage()
        }
    }

    // The UI follows the phone (owner: portrait and landscape). Landscape: status strip on top, controls column on the
    // right. Portrait: status strip on top, controls at the bottom. Design system: ui/CameraTheme.kt (D49).
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val recording = state.camera is CameraState.Recording
    CameraTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Preview(viewModel)
            // Right after the preview: its tap-to-focus layer stays under the other controls.
            ManualControlsLayer(
                viewModel.manualControls,
                state.profile.takeIf { state.camera !is CameraState.Closed },
                bottomInset = if (portrait) PORTRAIT_BAR else 0.dp,
                endInset = if (portrait) 0.dp else SIDE_COLUMN_CLEARANCE
            )
            ShutterFlash(viewModel)
            // The end padding keeps every status pill clear of the monitor button (and of the controls column in landscape).
            val statusEnd = if (portrait) MONITOR_CLEARANCE else SIDE_COLUMN_CLEARANCE + MONITOR_CLEARANCE
            TopStatus(
                state,
                status,
                health,
                viewModel,
                recording,
                stacked = portrait,
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = CameraDimens.edge, top = CameraDimens.edge, end = statusEnd)
            )
            if (portrait) {
                state.audio?.let {
                    AudioMeterBars(it.left, it.right, it.clip, Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(CameraDimens.edge))
                }
                BottomControls(state, viewModel, onOpenSettings, onOpenDiagnostics, Modifier.align(Alignment.BottomCenter))
                MonitorPanel(viewModel, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 64.dp, end = CameraDimens.edge))
            } else {
                state.audio?.let {
                    AudioMeterBars(it.left, it.right, it.clip, Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(CameraDimens.edge))
                }
                SideControls(state, viewModel, onOpenSettings, onOpenDiagnostics, Modifier.align(Alignment.CenterEnd))
                MonitorPanel(viewModel, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = CameraDimens.edge, end = SIDE_COLUMN_CLEARANCE))
            }
            SecondCameraPip(
                viewModel.multicam,
                Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = PIP_START, bottom = pipBottom(portrait))
            )
            CameraSnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp))
            HealthBanner(health.alerts, Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 8.dp).fillMaxWidth(BANNER_WIDTH))
        }
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

/** The status strip (timecode and mode badges, or the photo status), then health readouts and any storage warning. */
@Composable
private fun TopStatus(
    state: CameraUiState,
    status: StatusBarState,
    health: HealthState,
    viewModel: CameraViewModel,
    recording: Boolean,
    stacked: Boolean,
    modifier: Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Portrait is too narrow for the strip and the storage pill side by side: the pill got squeezed to a circle.
        if (stacked) {
            PhotoStatusOr(viewModel) { VideoStatus(state, recording) }
            StoragePicker(viewModel.storageChoice, recording = recording) { StorageReadout(state) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                PhotoStatusOr(viewModel) { VideoStatus(state, recording) }
                StoragePicker(viewModel.storageChoice, recording = recording) { StorageReadout(state) }
            }
        }
        // Health readouts (battery, heat, dropped frames, bitrate, GPU) and warnings stack under the strip, left-aligned,
        // so they never reach the REC button (right column in landscape, bottom bar in portrait).
        HealthIndicators(health)
        StatusBar(status, proxy = viewModel.multicam.state.collectAsStateWithLifecycle().value.proxyRecording)
        StorageWarning(state)
    }
}

@Composable
private fun VideoStatus(state: CameraUiState, recording: Boolean) {
    val profile = state.profile
    val badges = buildList {
        if (profile != null) {
            add("${RecordingProfile.sizeLabel(profile.height)}${profile.fps}")
            add(codecName(state.codec))
            if (profile.tenBit) add(stringResource(R.string.ui_badge_ten_bit))
        }
    }
    StatusStrip(recording = recording, timecode = formatTimecode(state.elapsedMs, profile?.fps ?: 0), badges = badges) {
        when (val camera = state.camera) {
            is CameraState.Failed -> StatusBadge(stringResource(R.string.camera_failed, camera.message), tone = Tone.ALERT)
            CameraState.Closed -> StatusBadge(stringResource(R.string.camera_opening))
            else -> Unit
        }
    }
}

/** Where takes go and the minutes left, as one tappable pill (it opens [StoragePicker]). */
@Composable
private fun StorageReadout(state: CameraUiState) {
    val storage = state.storage ?: return
    val place = storage.place ?: stringResource(R.string.storage_default)
    val warn = storage.fellBack || storage.unavailable || storage.space != FreeSpaceStatus.OK
    val minutes = storage.minutesLeft?.let { stringResource(R.string.ui_minutes_short, it) }
    val text = listOfNotNull(storage.place, minutes).joinToString(" · ").ifEmpty { "—" }
    ScrimSurface(shape = CameraShapes.pill) {
        StatusReadout(
            icon = R.drawable.ic_cam_storage,
            text = text,
            tone = if (warn) Tone.WARNING else Tone.NORMAL,
            contentDescription = stringResource(R.string.ui_storage_left, listOfNotNull(place, minutes).joinToString(", ")),
            modifier = Modifier.heightIn(min = CameraDimens.touch).padding(horizontal = 12.dp)
        )
    }
}

/** Fell back, unavailable or low space: an amber pill under the strip, only when something is wrong. */
@Composable
private fun StorageWarning(state: CameraUiState) {
    val storage = state.storage ?: return
    val parts = buildList {
        if (storage.fellBack) add(stringResource(R.string.storage_fell_back))
        if (storage.unavailable) add(stringResource(R.string.storage_unavailable))
        storage.space.messageRes?.let { add(stringResource(it)) }
    }
    if (parts.isEmpty()) return
    ScrimSurface(shape = CameraShapes.pill) {
        Text(
            parts.joinToString(" · "),
            color = CameraTheme.colors.warning,
            style = CameraTheme.type.label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** Landscape: a column on the right edge. Settings and mode on top, REC in the middle, image settings below. */
@Composable
private fun SideControls(state: CameraUiState, viewModel: CameraViewModel, onOpenSettings: () -> Unit, onOpenDiagnostics: () -> Unit, modifier: Modifier) {
    val recording = state.camera is CameraState.Recording
    Column(
        modifier.fillMaxHeight().background(CameraTheme.colors.scrim).safeDrawingPadding().padding(horizontal = CameraDimens.edge, vertical = CameraDimens.gap),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
            CameraIconButton(R.drawable.ic_cam_settings, stringResource(R.string.camera_settings), onClick = onOpenSettings, enabled = !recording)
            CaptureModeToggle(viewModel, enabled = !recording)
        }
        ShutterOr(viewModel) {
            RecordButton(recording, enabled = recording || state.camera is CameraState.Previewing, onToggle = { viewModel.toggleRecording() })
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
            VideoOnly(viewModel) {
                CodecPicker(state, enabled = !recording, onPick = viewModel::selectCodec)
                LutPicker(viewModel.looks, recording)
                ProfilePicker(state, enabled = !recording, onPick = viewModel::selectProfile)
            }
            CameraIconButton(R.drawable.ic_cam_info, stringResource(R.string.camera_diagnostics), onClick = onOpenDiagnostics, enabled = !recording)
        }
    }
}

/** Portrait: image settings in a row above REC, settings and mode on its left, diagnostics on its right. */
@Composable
private fun BottomControls(state: CameraUiState, viewModel: CameraViewModel, onOpenSettings: () -> Unit, onOpenDiagnostics: () -> Unit, modifier: Modifier) {
    val recording = state.camera is CameraState.Recording
    Column(
        modifier.fillMaxWidth().background(CameraTheme.colors.scrim).safeDrawingPadding().padding(horizontal = CameraDimens.edge, vertical = CameraDimens.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CameraDimens.gap)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
            VideoOnly(viewModel) {
                CodecPicker(state, enabled = !recording, onPick = viewModel::selectCodec)
                LutPicker(viewModel.looks, recording)
                ProfilePicker(state, enabled = !recording, onPick = viewModel::selectProfile)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
                CameraIconButton(R.drawable.ic_cam_settings, stringResource(R.string.camera_settings), onClick = onOpenSettings, enabled = !recording)
                CaptureModeToggle(viewModel, enabled = !recording)
            }
            ShutterOr(viewModel) {
                RecordButton(recording, enabled = recording || state.camera is CameraState.Previewing, onToggle = { viewModel.toggleRecording() })
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                CameraIconButton(R.drawable.ic_cam_info, stringResource(R.string.camera_diagnostics), onClick = onOpenDiagnostics, enabled = !recording)
            }
        }
    }
}

/** H.264 (widest compatibility, 8-bit), HEVC (default) or AV1 where the phone has an encoder. */
@Composable
private fun CodecPicker(state: CameraUiState, enabled: Boolean, onPick: (VideoCodec) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = codecName(state.codec)
    Box {
        QuickChip(name, onClick = { open = true }, enabled = enabled, active = open, contentDescription = "${stringResource(R.string.ui_codec)}: $name")
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
private fun ProfilePicker(state: CameraUiState, enabled: Boolean, onPick: (RecordingProfile) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = state.profile?.label ?: "—"
    Box {
        QuickChip(
            label,
            onClick = { open = true },
            icon = R.drawable.ic_cam_video,
            enabled = enabled,
            active = open,
            contentDescription = "${stringResource(R.string.ui_format)}: $label"
        )
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
    is CameraMessage.Stopped -> stringResource(stopReasonText(message.reason), message.fileName)
    CameraMessage.ThermalWarning -> stringResource(R.string.camera_thermal_warning)
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

/** Keeps the monitor button clear of the landscape controls column (REC 76 dp + padding). */
private val SIDE_COLUMN_CLEARANCE = 132.dp

/** Room for the monitor button at the top end (48 dp + margins): status pills stop before it. */
private val MONITOR_CLEARANCE = 72.dp

/** The health banner spans most of the top edge, clear of REC (right column or bottom bar). */
private const val BANNER_WIDTH = 0.7f
