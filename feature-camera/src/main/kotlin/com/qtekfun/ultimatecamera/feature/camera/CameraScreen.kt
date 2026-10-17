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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.storage.FreeSpaceStatus
import com.qtekfun.ultimatecamera.feature.camera.remote.CameraRoleLayer
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
fun CameraScreen(viewModel: CameraViewModel, onOpenSettings: () -> Unit, onOpenDiagnostics: () -> Unit, onOpenMulticam: () -> Unit = {}) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] == true || context.granted(Manifest.permission.CAMERA)
        viewModel.audioAllowed = context.granted(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) {
        viewModel.audioAllowed = context.granted(Manifest.permission.RECORD_AUDIO)
        // Notifications: the ongoing notification of a take or role running off screen (D66).
        if (!cameraGranted || !viewModel.audioAllowed || !context.granted(Manifest.permission.POST_NOTIFICATIONS)) launcher.launch(STARTUP_PERMISSIONS)
    }
    if (!cameraGranted) {
        PermissionNeeded(onRequest = { launcher.launch(STARTUP_PERMISSIONS) }, onOpenSettings)
        return
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    // Off screen a take or the camera role may go on in a foreground service (D66); otherwise the camera closes.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    // Leaving for another screen frees the camera (auto-configuration needs it); coming back reopens it on ON_START.
    DisposableEffect(viewModel) { onDispose { viewModel.onStop() } }
    DisplayRotation(context, viewModel::setDisplayRotation)

    // Status (2 Hz), health (1 Hz) and the take's timer and meter (15 Hz) are collected where they are shown, so the
    // whole screen does not recompose at their rates (D68).
    val state by viewModel.state.collectAsStateWithLifecycle()
    val volumeKeys by viewModel.volumeKeys.collectAsStateWithLifecycle()
    // Volume keys: REC in video mode, shutter in photo mode (D41, D47).
    VolumeKeyRecording(enabled = volumeKeys) { if (viewModel.photo.active) viewModel.takePhoto() else viewModel.toggleRecording() }
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { messageText(it) }
    val actionLabel = (state.message as? CameraMessage.StepDownOffer)?.let { stringResource(R.string.camera_step_down_action) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            val result = snackbar.showSnackbar(messageText, actionLabel, duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.acceptStepDown()
            viewModel.consumeMessage()
        }
    }

    // The UI follows the phone (owner: portrait and landscape). Landscape: status strip on top, controls column on the
    // right. Portrait: status strip on top, controls at the bottom. Design system: ui/CameraTheme.kt (D49).
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val recording = state.camera is CameraState.Recording
    val photoMode = viewModel.photo.state.collectAsStateWithLifecycle().value.mode == CaptureMode.PHOTO
    CameraTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Preview(viewModel)
            // Right after the preview: its tap-to-focus layer stays under the other controls.
            ManualControlsLayer(
                viewModel.manualControls,
                state.profile.takeIf { state.camera !is CameraState.Closed },
                bottomInset = if (portrait) PORTRAIT_BAR else 0.dp,
                endInset = if (portrait) 0.dp else SIDE_COLUMN_CLEARANCE,
                lenses = viewModel.lenses.takeIf { !photoMode },
                recording = recording
            ) {
                // Landscape: the side column has no room left, the stabilisation and pair chips sit by the lens pills.
                if (!portrait) {
                    StabilizationChip(viewModel.lenses, recording)
                    SecondCameraChip(viewModel.multicam, viewModel.lenses, recording)
                }
            }
            ShutterFlash(viewModel)
            // Portrait: the strip gets the full width (the monitor button sits below it) and the rows under it keep clear of
            // the monitor button. Landscape: everything keeps clear of the monitor button and the controls column.
            val stripEnd = if (portrait) CameraDimens.edge else SIDE_COLUMN_CLEARANCE + MONITOR_CLEARANCE
            val belowEnd = if (portrait) MONITOR_CLEARANCE - CameraDimens.edge else 0.dp
            TopStatus(
                state,
                viewModel,
                recording,
                stacked = portrait,
                belowEnd = belowEnd,
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(start = CameraDimens.edge, top = CameraDimens.edge, end = stripEnd)
            )
            if (portrait) {
                TakeAudioMeter(viewModel, Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(CameraDimens.edge))
                BottomControls(state, viewModel, onOpenSettings, onOpenDiagnostics, onOpenMulticam, Modifier.align(Alignment.BottomCenter))
                MonitorPanel(viewModel, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 64.dp, end = CameraDimens.edge))
            } else {
                TakeAudioMeter(viewModel, Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(CameraDimens.edge))
                SideControls(state, viewModel, onOpenSettings, onOpenDiagnostics, onOpenMulticam, Modifier.align(Alignment.CenterEnd))
                MonitorPanel(viewModel, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = CameraDimens.edge, end = SIDE_COLUMN_CLEARANCE))
            }
            HdrLayer(
                viewModel.hdr,
                recording,
                Modifier.align(Alignment.TopStart).safeDrawingPadding()
                    .padding(start = CameraDimens.edge, top = if (portrait) HDR_CHIP_TOP_PORTRAIT else HDR_CHIP_TOP)
            )
            SecondCameraPip(
                viewModel.multicam,
                Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = PIP_START, bottom = pipBottom(portrait))
            )
            // Toasts sit low on the preview, just above the manual-controls bar, away from the status column and REC.
            CameraSnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(
                        bottom = if (portrait) PORTRAIT_BAR + CONTROLS_BAR_CLEARANCE else CONTROLS_BAR_CLEARANCE,
                        end = if (portrait) 0.dp else SIDE_COLUMN_CLEARANCE
                    )
            )
            CameraRoleLayer(
                viewModel.cameraRole,
                state,
                viewModel.live,
                chipTop = if (portrait) 120.dp else 64.dp,
                chipEnd = if (portrait) CameraDimens.edge else SIDE_COLUMN_CLEARANCE
            )
        }
    }
}

/** The take's audio meter; reads the 15 Hz [TakeLive] itself so only the meter recomposes at that rate. */
@Composable
private fun TakeAudioMeter(viewModel: CameraViewModel, modifier: Modifier) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    live.audio?.let { AudioMeterBars(it.left, it.right, it.clip, modifier) }
}

@Composable
private fun Preview(viewModel: CameraViewModel) {
    val load by viewModel.previewLoad.collectAsStateWithLifecycle()
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        update = { view -> view.capPreview(load.maxLongSide) },
        factory = { ctx ->
            SurfaceView(ctx).apply {
                // Modest or hot phone: the preview buffer is capped (D68); the compositor scales it to the view.
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> (v as SurfaceView).capPreview(viewModel.previewLoad.value.maxLongSide) }
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

/** Caps the surface buffer to [maxLongSide] (0 = the view's own size); only changes it when the size differs. */
private fun SurfaceView.capPreview(maxLongSide: Int) {
    if (width <= 0 || height <= 0) return
    val capped = PreviewSizeCap.cap(width, height, maxLongSide)
    if (capped == tag) return
    tag = capped
    if (capped == null) holder.setSizeFromLayout() else holder.setFixedSize(capped.first, capped.second)
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
private fun TopStatus(state: CameraUiState, viewModel: CameraViewModel, recording: Boolean, stacked: Boolean, belowEnd: Dp, modifier: Modifier) {
    val timeOfDay by viewModel.timecode.label.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Portrait is too narrow for the strip and the storage pill side by side: the pill got squeezed to a circle.
        if (stacked) {
            PhotoStatusOr(viewModel) { VideoStatus(state, viewModel, recording, timeOfDay) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                PhotoStatusOr(viewModel) { VideoStatus(state, viewModel, recording, timeOfDay) }
                StoragePicker(viewModel.storageChoice, recording = recording) { StorageReadout(state) }
            }
        }
        // Health readouts (battery, heat, dropped frames, bitrate, GPU), warnings and the alert banner stack under the strip,
        // left-aligned, so they never cover the timecode nor reach the REC button.
        Column(Modifier.padding(end = belowEnd), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (stacked) StoragePicker(viewModel.storageChoice, recording = recording) { StorageReadout(state) }
            HealthReadouts(viewModel, state)
        }
    }
}

/** Health (1 Hz) and take status (2 Hz), collected here so only this column recomposes at their rates (D68). */
@Composable
private fun HealthReadouts(viewModel: CameraViewModel, state: CameraUiState) {
    val health by viewModel.health.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    HealthIndicators(health)
    StatusBar(status, proxy = viewModel.multicam.state.collectAsStateWithLifecycle().value.proxyRecording)
    StorageWarning(state)
    HealthBanner(health.alerts, Modifier.widthIn(max = BANNER_MAX_WIDTH))
}

/** [timeOfDay]: the time-of-day timecode when chosen in Settings (D58), else null for the take's running time. */
@Composable
private fun VideoStatus(state: CameraUiState, viewModel: CameraViewModel, recording: Boolean, timeOfDay: String?) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    val profile = state.profile
    val badges = buildList {
        if (profile != null) {
            add("${RecordingProfile.sizeLabel(profile.height)}${profile.fps}")
            add(codecName(state.codec))
            if (profile.tenBit) add(stringResource(R.string.ui_badge_ten_bit))
        }
    }
    StatusStrip(
        recording = recording,
        timecode = timeOfDay ?: formatTimecode(live.elapsedMs, profile?.fps ?: 0),
        badges = badges,
        elapsedMs = live.elapsedMs.takeIf { timeOfDay == null }
    ) {
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
private fun SideControls(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenMulticam: () -> Unit,
    modifier: Modifier
) {
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
            TakeRecordButton(viewModel, recording, enabled = recording || state.camera is CameraState.Previewing)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
            VideoOnly(viewModel) {
                CodecPicker(state, enabled = !recording, onPick = viewModel::selectCodec)
                LutPicker(viewModel.looks, recording)
                ProfilePicker(state, enabled = !recording, onPick = viewModel::selectProfile)
            }
            PhotoHdrChip(viewModel)
            PhotoStyleChip(viewModel)
            MulticamEntry(onOpenMulticam, enabled = !recording)
            CameraIconButton(R.drawable.ic_cam_info, stringResource(R.string.camera_diagnostics), onClick = onOpenDiagnostics, enabled = !recording)
        }
    }
}

/** Portrait: image settings in a row above REC, settings and mode on its left, diagnostics on its right. */
@Composable
private fun BottomControls(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenMulticam: () -> Unit,
    modifier: Modifier
) {
    val recording = state.camera is CameraState.Recording
    Column(
        modifier.fillMaxWidth().background(CameraTheme.colors.scrim).safeDrawingPadding().padding(horizontal = CameraDimens.edge, vertical = CameraDimens.gap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CameraDimens.gap)
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
            VideoOnly(viewModel) {
                CodecPicker(state, enabled = !recording, onPick = viewModel::selectCodec)
                LutPicker(viewModel.looks, recording)
                ProfilePicker(state, enabled = !recording, onPick = viewModel::selectProfile)
                StabilizationChip(viewModel.lenses, recording)
                SecondCameraChip(viewModel.multicam, viewModel.lenses, recording)
            }
            PhotoHdrChip(viewModel)
            PhotoStyleChip(viewModel)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
                CameraIconButton(R.drawable.ic_cam_settings, stringResource(R.string.camera_settings), onClick = onOpenSettings, enabled = !recording)
                CaptureModeToggle(viewModel, enabled = !recording)
            }
            ShutterOr(viewModel) {
                TakeRecordButton(viewModel, recording, enabled = recording || state.camera is CameraState.Previewing)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap, Alignment.End)) {
                MulticamEntry(onOpenMulticam, enabled = !recording)
                CameraIconButton(R.drawable.ic_cam_info, stringResource(R.string.camera_diagnostics), onClick = onOpenDiagnostics, enabled = !recording)
            }
        }
    }
}

/**
 * REC with the take time in its TalkBack state (D67). Reads the 15 Hz [CameraViewModel.live] here, reduced to whole
 * seconds, so only this button recomposes once a second, not the control column (D68).
 */
@Composable
private fun TakeRecordButton(viewModel: CameraViewModel, recording: Boolean, enabled: Boolean) {
    val live = viewModel.live.collectAsStateWithLifecycle()
    val seconds by remember { derivedStateOf { live.value.elapsedMs / MS_PER_SECOND } }
    RecordButton(recording, enabled = enabled, onToggle = { viewModel.toggleRecording() }, elapsedMs = seconds * MS_PER_SECOND)
}

private const val MS_PER_SECOND = 1000L

/** Opens the multicamera setup (role, pairing; feature-multicam). */
@Composable
private fun MulticamEntry(onOpen: () -> Unit, enabled: Boolean) =
    CameraIconButton(R.drawable.ic_cam_monitor, stringResource(R.string.camera_multicam), onClick = onOpen, enabled = enabled)

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
    is CameraMessage.Interrupted -> stringResource(R.string.camera_take_interrupted, message.fileName)
    is CameraMessage.StepDownOffer -> stringResource(R.string.camera_step_down_offer, message.to)
    is CameraMessage.SteppedDown -> stringResource(R.string.camera_stepped_down, message.from, message.to)
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

private val STARTUP_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

/** Keeps the monitor button clear of the landscape controls column (REC 76 dp + padding). */
private val SIDE_COLUMN_CLEARANCE = 132.dp

/** Room for the monitor button at the top end (48 dp + margins): status pills stop before it. */
private val MONITOR_CLEARANCE = 72.dp

/** The health banner spans most of the top edge, clear of REC (right column or bottom bar). */
private val BANNER_MAX_WIDTH = 480.dp

/** Room for the manual-controls chip bar (48 dp readouts + padding) that toasts sit above. */
private val CONTROLS_BAR_CLEARANCE = 72.dp
