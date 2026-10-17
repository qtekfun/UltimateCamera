package com.qtekfun.ultimatecamera.feature.multicam.monitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.feature.camera.HealthBanner
import com.qtekfun.ultimatecamera.feature.camera.R as CamR
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraDimens
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraIconButton
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraShapes
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.OptionChip
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip
import com.qtekfun.ultimatecamera.feature.camera.ui.Tone
import com.qtekfun.ultimatecamera.feature.multicam.R

/**
 * Entry of the controller side: the master grid when several cameras are paired, the monitor straight away for one;
 * a tile opens its camera's monitor, Back returns to the grid (then [onBack]).
 */
@Composable
fun MulticamControlRoute(viewModel: RemoteCamerasViewModel, onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val single = viewModel.cameras.singleOrNull()?.id
    val shown = open ?: single
    if (shown != null) {
        BackHandler(enabled = single == null) { open = null }
        MonitorRoute(viewModel, shown, onBack = { if (single == null) open = null else onBack() })
    } else {
        MasterRoute(viewModel, onOpen = { open = it }, onBack = onBack)
    }
}

@Composable
fun MasterRoute(viewModel: RemoteCamerasViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val cameras by viewModel.ui.collectAsStateWithLifecycle()
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    MasterScreen(
        cameras = cameras,
        thumbnails = thumbnails,
        videoHost = { viewModel.camera(it)?.videoHost() },
        rtt = viewModel::rtt,
        onThumbnails = viewModel::setThumbnails,
        onRecordAll = viewModel::recordAll,
        onStopAll = viewModel::stopAll,
        onOpen = onOpen,
        onBack = onBack
    )
}

/**
 * Master screen (REQ-NET-003/005): up to four paired cameras in a grid, each with its status and alerts, video
 * thumbnails where the network allows (D57), and REC all / STOP all. A 2 × 2 grid in landscape; one column that
 * scrolls in portrait, so each tile stays wide enough for its status strip.
 */
@Composable
fun MasterScreen(
    cameras: List<RemoteCameraUi>,
    thumbnails: Boolean,
    videoHost: (String) -> String?,
    rtt: (String) -> Long?,
    onThumbnails: (Boolean) -> Unit,
    onRecordAll: () -> Unit,
    onStopAll: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit
) {
    CameraTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(CameraDimens.edge)) {
            val landscape = maxWidth > maxHeight
            Column(verticalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
                MasterBar(cameras, thumbnails, onThumbnails, onRecordAll, onStopAll, onBack)
                if (cameras.isEmpty()) {
                    ScrimText(stringResource(R.string.mc_mon_no_cameras))
                } else {
                    val rows = cameras.withIndex().chunked(if (landscape) 2 else 1)
                    Column(
                        Modifier.then(if (landscape) Modifier else Modifier.verticalScroll(rememberScrollState())),
                        verticalArrangement = Arrangement.spacedBy(CameraDimens.gap)
                    ) {
                        rows.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap)) {
                                row.forEach { (index, ui) ->
                                    val video = TileVideoPolicy.showVideo(thumbnails, index, ui, rtt(ui.id))
                                    CameraTile(ui, if (video) videoHost(ui.id) else null, Modifier.weight(1f), onOpen)
                                }
                                if (landscape && row.size == 1) Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterBar(
    cameras: List<RemoteCameraUi>,
    thumbnails: Boolean,
    onThumbnails: (Boolean) -> Unit,
    onRecordAll: () -> Unit,
    onStopAll: () -> Unit,
    onBack: () -> Unit
) {
    val recording = cameras.count { it.rec == RecUi.RECORDING }
    val colors = CameraTheme.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(CameraDimens.gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CameraIconButton(CamR.drawable.ic_cam_close, stringResource(R.string.mc_mon_back), onBack)
        Text(stringResource(R.string.mc_mon_title_master), style = CameraTheme.type.title, color = colors.content)
        Text(
            stringResource(R.string.mc_mon_recording_count, recording, cameras.size),
            style = CameraTheme.type.label,
            color = if (recording > 0) colors.rec else colors.contentMuted
        )
        OptionChip(stringResource(R.string.mc_mon_thumbnails), thumbnails, { onThumbnails(!thumbnails) })
        QuickChip(stringResource(R.string.mc_mon_rec_all), onRecordAll, icon = CamR.drawable.ic_cam_record, enabled = cameras.any { it.controlsEnabled })
        QuickChip(stringResource(R.string.mc_mon_stop_all), onStopAll, icon = CamR.drawable.ic_cam_stop, enabled = cameras.any { it.controlsEnabled })
    }
}

/** One camera: video thumbnail or a status-only surface, the link pill, the status strip and its alerts. */
@Composable
private fun CameraTile(ui: RemoteCameraUi, videoHost: String?, modifier: Modifier, onOpen: (String) -> Unit) {
    val colors = CameraTheme.colors
    var video by remember { mutableStateOf(VideoState.UNAVAILABLE) }
    val edge = when {
        ui.rec == RecUi.RECORDING -> colors.rec
        tileTone(ui) == Tone.ALERT -> colors.rec
        tileTone(ui) == Tone.WARNING -> colors.warning
        else -> colors.hairline
    }
    val description = stringResource(R.string.mc_mon_open_monitor, ui.name)
    Box(
        modifier
            .aspectRatio(TILE_ASPECT)
            .clip(CameraShapes.surface)
            .background(colors.scrimStrong)
            .border(2.dp, edge, CameraShapes.surface)
            .clickable(role = Role.Button, onClickLabel = description) { onOpen(ui.id) }
    ) {
        if (videoHost != null) RemoteVideo(videoHost, enabled = true, onState = { video = it })
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinkPill(ui)
            RemoteStatusStrip(ui, compact = true)
            HealthBanner(ui.alerts)
            if (videoHost != null && video != VideoState.LIVE) {
                Text(stringResource(R.string.mc_mon_video_unavailable), style = CameraTheme.type.label, color = colors.contentMuted)
            }
            recHint(ui.rec)?.let { Text(stringResource(it), style = CameraTheme.type.label, color = colors.contentMuted) }
        }
    }
}

private const val TILE_ASPECT = 16f / 9f
