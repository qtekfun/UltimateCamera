package com.qtekfun.ultimatecamera.feature.multicam.monitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.feature.camera.R as CamR
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraDimens
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraIconButton
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraPanel
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.LockChip
import com.qtekfun.ultimatecamera.feature.camera.ui.OptionChip
import com.qtekfun.ultimatecamera.feature.camera.ui.PanelSection
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip
import com.qtekfun.ultimatecamera.feature.camera.ui.RecordButton
import com.qtekfun.ultimatecamera.feature.camera.ui.ScrimSurface
import com.qtekfun.ultimatecamera.feature.camera.ui.ValueReadout
import com.qtekfun.ultimatecamera.feature.multicam.R

/** The remote control panels, one open at a time. */
enum class RemotePanel { ISO, SHUTTER, EV, FOCUS, WB, PROFILE, LUT }

/** What the monitor screen can ask of its camera. */
interface MonitorActions {
    fun toggleRecording()

    fun send(body: CommandBody)

    fun dismissError()
}

/** Monitor screen bound to the shared [RemoteCamerasViewModel]: one camera, by id. */
@Composable
fun MonitorRoute(viewModel: RemoteCamerasViewModel, cameraId: String, onBack: () -> Unit) {
    val all by viewModel.ui.collectAsStateWithLifecycle()
    val ui = all.firstOrNull { it.id == cameraId } ?: return
    val camera = viewModel.camera(cameraId) ?: return
    val actions = remember(viewModel, cameraId) {
        object : MonitorActions {
            override fun toggleRecording() = viewModel.toggleRecording(cameraId)

            override fun send(body: CommandBody) = viewModel.send(cameraId, body)

            override fun dismissError() = viewModel.clearError(cameraId)
        }
    }
    MonitorScreen(ui, camera.videoHost(), actions, onBack)
}

/**
 * Full-screen monitor of one paired camera (REQ-NET-003/004/007): its WebRTC video, the camera's status strip and
 * health alerts mirrored on top, the link state, and remote REC and image controls. Landscape puts the controls in a
 * column on the right, portrait at the bottom, like the camera screen (D48, D49).
 */
@Composable
fun MonitorScreen(ui: RemoteCameraUi, videoHost: String?, actions: MonitorActions, onBack: () -> Unit) {
    var panel by rememberSaveable { mutableStateOf<RemotePanel?>(null) }
    var video by remember { mutableStateOf(VideoState.CONNECTING) }
    var ev by rememberSaveable { mutableStateOf(0f) }
    BackHandler(enabled = panel != null) { panel = null }
    CameraTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val landscape = maxWidth > maxHeight
            val controls: @Composable () -> Unit = {
                RemoteControls(ui, ev, panel, onPanel = { panel = if (panel == it) null else it }, actions)
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        MonitorStage(ui, videoHost, video, { video = it }, actions, onBack)
                    }
                    Column(
                        Modifier.fillMaxHeight().safeDrawingPadding().padding(CameraDimens.edge).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(CameraDimens.gap, Alignment.CenterVertically)
                    ) {
                        RecColumn(ui, actions)
                        controls()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        MonitorStage(ui, videoHost, video, { video = it }, actions, onBack)
                    }
                    Column(
                        Modifier.fillMaxWidth().safeDrawingPadding().padding(CameraDimens.edge),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(CameraDimens.gap)
                    ) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap)) { controls() }
                        RecColumn(ui, actions)
                    }
                }
            }
            panel?.let { open ->
                RemotePanelSheet(
                    open,
                    ui,
                    ev,
                    onEv = {
                        ev = it
                        actions.send(RemoteCommands.ev(it))
                    },
                    actions = actions,
                    onClose = { panel = null },
                    modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter).safeDrawingPadding().padding(CameraDimens.edge)
                )
            }
        }
    }
}

/** Video plus the overlays drawn over it: back, status strip, link pill, alerts, video state. */
@Composable
private fun MonitorStage(
    ui: RemoteCameraUi,
    videoHost: String?,
    video: VideoState,
    onVideo: (VideoState) -> Unit,
    actions: MonitorActions,
    onBack: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        RemoteVideo(videoHost, enabled = ui.link != LinkUi.OFFLINE, onState = onVideo)
        if (video != VideoState.LIVE) {
            Text(
                stringResource(if (video == VideoState.CONNECTING) R.string.mc_mon_video_connecting else R.string.mc_mon_video_unavailable),
                style = CameraTheme.type.body,
                color = CameraTheme.colors.contentMuted,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Column(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(CameraDimens.edge),
            verticalArrangement = Arrangement.spacedBy(CameraDimens.gap)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap), verticalAlignment = Alignment.CenterVertically) {
                CameraIconButton(CamR.drawable.ic_cam_close, stringResource(R.string.mc_mon_back), onBack)
                LinkPill(ui)
            }
            RemoteStatusStrip(ui)
            RemoteAlerts(ui, actions::dismissError, Modifier.widthIn(max = 520.dp))
        }
    }
}

@Composable
private fun RecColumn(ui: RemoteCameraUi, actions: MonitorActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RecordButton(recording = ui.rec.showsRecording, onToggle = actions::toggleRecording, enabled = ui.controlsEnabled && !ui.rec.pending)
        recHint(ui.rec)?.let { Text(stringResource(it), style = CameraTheme.type.label, color = CameraTheme.colors.contentMuted) }
    }
}

/** The camera's image controls: ISO, shutter, EV, focus, WB readouts (each opens its panel), locks, profile and LUT. */
@Composable
private fun RemoteControls(ui: RemoteCameraUi, ev: Float, open: RemotePanel?, onPanel: (RemotePanel) -> Unit, actions: MonitorActions) {
    val c = ui.capture
    val auto = stringResource(R.string.mc_mon_auto)
    val enabled = ui.controlsEnabled
    val items: List<@Composable () -> Unit> = listOf(
        {
            ValueReadout(
                stringResource(R.string.mc_mon_iso),
                if (c.autoExposure) auto else c.iso?.toString() ?: "—",
                { onPanel(RemotePanel.ISO) },
                active =
                    open == RemotePanel.ISO,
                enabled = enabled
            )
        },
        {
            ValueReadout(
                stringResource(R.string.mc_mon_shutter),
                if (c.autoExposure) auto else c.shutter ?: "—",
                { onPanel(RemotePanel.SHUTTER) },
                active =
                    open == RemotePanel.SHUTTER,
                enabled = enabled
            )
        },
        {
            ValueReadout(
                stringResource(R.string.mc_mon_ev),
                RemoteCommands.evText(ev),
                { onPanel(RemotePanel.EV) },
                active = open == RemotePanel.EV,
                enabled =
                    enabled && c.autoExposure
            )
        },
        {
            ValueReadout(
                stringResource(R.string.mc_mon_focus),
                if (c.autoFocus) auto else c.focusDiopters?.let { RemoteCommands.focusText(it) } ?: "—",
                { onPanel(RemotePanel.FOCUS) },
                active = open == RemotePanel.FOCUS,
                enabled = enabled
            )
        },
        {
            ValueReadout(
                stringResource(R.string.mc_mon_wb),
                if (c.autoWhiteBalance) {
                    auto
                } else {
                    c.kelvin?.let {
                        "${it}K"
                    } ?: "—"
                },
                { onPanel(RemotePanel.WB) },
                active =
                    open == RemotePanel.WB,
                enabled = enabled
            )
        },
        {
            LockChip(stringResource(R.string.mc_mon_ae_lock), !c.autoExposure, {
                actions.send(RemoteCommands.exposureLock(c, c.autoExposure))
            }, enabled = enabled)
        },
        { LockChip(stringResource(R.string.mc_mon_af_lock), !c.autoFocus, { actions.send(RemoteCommands.focusLock(c, c.autoFocus)) }, enabled = enabled) },
        {
            LockChip(stringResource(R.string.mc_mon_awb_lock), !c.autoWhiteBalance, {
                actions.send(RemoteCommands.whiteBalanceLock(c, c.autoWhiteBalance))
            }, enabled = enabled)
        },
        {
            QuickChip(
                ui.profileLabel ?: stringResource(R.string.mc_mon_profile),
                { onPanel(RemotePanel.PROFILE) },
                icon = CamR.drawable.ic_cam_video,
                enabled = enabled && !ui.rec.showsRecording,
                active = open == RemotePanel.PROFILE,
                contentDescription = stringResource(R.string.mc_mon_profile)
            )
        },
        {
            QuickChip(
                ui.luts.firstOrNull { it.id == ui.lutId }?.label ?: ui.lutId ?: stringResource(R.string.mc_mon_lut_none),
                { onPanel(RemotePanel.LUT) },
                icon = CamR.drawable.ic_cam_lut,
                enabled = enabled,
                active = open == RemotePanel.LUT,
                contentDescription = stringResource(R.string.mc_mon_lut)
            )
        }
    )
    items.forEach { it() }
}

/** The open panel's choices; each tap sends one command (the status confirms it). */
@Composable
private fun RemotePanelSheet(
    panel: RemotePanel,
    ui: RemoteCameraUi,
    ev: Float,
    onEv: (Float) -> Unit,
    actions: MonitorActions,
    onClose: () -> Unit,
    modifier: Modifier
) {
    val c = ui.capture
    val title = stringResource(
        when (panel) {
            RemotePanel.ISO -> R.string.mc_mon_iso
            RemotePanel.SHUTTER -> R.string.mc_mon_shutter
            RemotePanel.EV -> R.string.mc_mon_ev
            RemotePanel.FOCUS -> R.string.mc_mon_focus
            RemotePanel.WB -> R.string.mc_mon_wb
            RemotePanel.PROFILE -> R.string.mc_mon_profile
            RemotePanel.LUT -> R.string.mc_mon_lut
        }
    )
    val auto = stringResource(R.string.mc_mon_auto)
    CameraPanel(title, onClose, modifier.widthIn(max = 420.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (panel) {
                RemotePanel.ISO -> {
                    OptionChip(auto, c.autoExposure, { actions.send(CommandBody.SetExposure(auto = true)) })
                    RemoteCommands.ISO_STEPS.forEach { v -> OptionChip("$v", !c.autoExposure && c.iso == v, { actions.send(RemoteCommands.iso(c, v)) }) }
                }
                RemotePanel.SHUTTER -> {
                    OptionChip(auto, c.autoExposure, { actions.send(CommandBody.SetExposure(auto = true)) })
                    RemoteCommands.SHUTTER_STEPS.forEach { v ->
                        val text = RemoteCameraMapper.shutterText(v)
                        OptionChip(text, !c.autoExposure && c.shutter == text, { actions.send(RemoteCommands.shutter(c, v)) })
                    }
                }
                RemotePanel.EV -> RemoteCommands.EV_STEPS.forEach { v -> OptionChip(RemoteCommands.evText(v), ev == v, { onEv(v) }) }
                RemotePanel.FOCUS -> {
                    OptionChip(auto, c.autoFocus, { actions.send(RemoteCommands.focus(null)) })
                    RemoteCommands.FOCUS_STEPS.forEach { v ->
                        OptionChip(RemoteCommands.focusText(v), !c.autoFocus && c.focusDiopters == v, { actions.send(RemoteCommands.focus(v)) })
                    }
                }
                RemotePanel.WB -> {
                    OptionChip(auto, c.autoWhiteBalance, { actions.send(RemoteCommands.whiteBalance(null)) })
                    RemoteCommands.KELVIN_STEPS.forEach { v ->
                        OptionChip("${v}K", !c.autoWhiteBalance && c.kelvin == v, { actions.send(RemoteCommands.whiteBalance(v)) })
                    }
                }
                RemotePanel.PROFILE -> Unit
                RemotePanel.LUT -> {
                    OptionChip(stringResource(R.string.mc_mon_lut_none), ui.lutId == null, { actions.send(CommandBody.SelectLut(null)) })
                    ui.luts.forEach { l -> OptionChip(l.label, ui.lutId == l.id, { actions.send(CommandBody.SelectLut(l.id)) }) }
                }
            }
        }
        if (panel == RemotePanel.PROFILE) ProfilePicker(ui, actions)
        if (listMissing(panel, ui)) {
            Text(stringResource(R.string.mc_mon_not_reported), style = CameraTheme.type.body, color = CameraTheme.colors.contentMuted)
        }
    }
}

/** Codec filter, then the camera's profiles under it. Refused while recording (the camera says BUSY). */
@Composable
private fun ProfilePicker(ui: RemoteCameraUi, actions: MonitorActions) {
    var codec by rememberSaveable { mutableStateOf<String?>(null) }
    val codecs = ProfileChoices.codecs(ui.profiles)
    if (codecs.size > 1) {
        PanelSection(stringResource(R.string.mc_mon_codec)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OptionChip(stringResource(R.string.mc_mon_all), codec == null, { codec = null })
                codecs.forEach { c -> OptionChip(c, codec == c, { codec = c }) }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ProfileChoices.profilesFor(ui.profiles, codec).forEach { p ->
            OptionChip(p.label, ui.profileId == p.id, { actions.send(CommandBody.SelectProfile(p.id)) }, enabled = !ui.rec.showsRecording)
        }
    }
}

/** A scrim behind a block of text for places without one. */
@Composable
internal fun ScrimText(text: String, modifier: Modifier = Modifier) {
    ScrimSurface(modifier) {
        Text(text, style = CameraTheme.type.body, color = CameraTheme.colors.content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

/** The camera did not send the list this panel picks from (an older camera, or its role not reporting it yet). */
private fun listMissing(panel: RemotePanel, ui: RemoteCameraUi): Boolean = when (panel) {
    RemotePanel.PROFILE -> ui.profiles.isEmpty()
    RemotePanel.LUT -> ui.luts.isEmpty()
    else -> false
}
