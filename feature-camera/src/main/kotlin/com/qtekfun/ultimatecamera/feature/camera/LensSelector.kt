package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.camera.LensCatalog
import com.qtekfun.ultimatecamera.core.camera.LensKind
import com.qtekfun.ultimatecamera.core.camera.VideoStabilization
import com.qtekfun.ultimatecamera.core.recording.PairBlock
import com.qtekfun.ultimatecamera.core.recording.PairOption
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraIconButton
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraShapes
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.OptionChip
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip

/**
 * The lens pills (`0.5 · 1× · 2 · 5`, the selected one with ×) and the front-camera toggle (REQ-CAM-001). Stops on
 * another camera id and the front toggle are disabled while recording; zoom stops on the open camera work mid-take.
 * While pinching ([zooming]) the current zoom replaces the selected pill's label.
 */
@Composable
internal fun LensSelector(wire: LensWire, recording: Boolean, zooming: Boolean, modifier: Modifier = Modifier) {
    val state by wire.state.collectAsStateWithLifecycle()
    val controls by wire.controls.collectAsStateWithLifecycle()
    val catalog = state.catalog ?: return
    val id = state.cameraId ?: return
    val stops = catalog.stopsFor(id)
    val selected = catalog.selected(id, controls.zoomRatio)
    val zoom = LensCatalog.formatFactor(catalog.displayZoom(id, controls.zoomRatio))
    if (stops.size <= 1 && catalog.front == null) return
    Row(
        modifier
            .clip(CameraShapes.pill)
            .background(CameraTheme.colors.scrim)
            .border(CameraTheme.hairlineBorder, CameraShapes.pill)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (stops.size > 1) {
            stops.forEach { stop ->
                val isSelected = stop == selected
                val label = when {
                    isSelected && zooming -> stringResource(R.string.lens_zoom, zoom)
                    isSelected -> stringResource(R.string.lens_zoom, stop.label)
                    else -> stop.label
                }
                OptionChip(label, selected = isSelected, onClick = { wire.select(stop) }, enabled = !recording || stop.cameraId == id)
            }
        } else if (zooming) {
            Text(stringResource(R.string.lens_zoom, zoom), color = CameraTheme.colors.content, style = CameraTheme.type.value)
        }
        if (catalog.front != null) {
            CameraIconButton(
                R.drawable.ic_cam_flip,
                stringResource(if (state.front) R.string.lens_back else R.string.lens_front),
                onClick = wire::toggleFront,
                enabled = !recording,
                selected = state.front
            )
        }
    }
}

/** Two-finger pinch on the preview: continuous zoom; [onZooming] reports when a pinch starts and ends. */
internal suspend fun PointerInputScope.detectPinch(onZoom: (Float) -> Unit, onZooming: (Boolean) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinching = false
        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2) {
                if (!pinching) onZooming(true)
                pinching = true
                val scale = event.calculateZoom()
                if (scale != 1f) onZoom(scale)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinching) onZooming(false)
    }
}

/** Stabilisation quick chip (REQ-CAM-003): electronic OFF / ON / preview, and optical, as the open camera offers them. */
@Composable
internal fun StabilizationChip(wire: LensWire, recording: Boolean) {
    val state by wire.state.collectAsStateWithLifecycle()
    val controls by wire.controls.collectAsStateWithLifecycle()
    val ranges by wire.ranges.collectAsStateWithLifecycle()
    val r = ranges ?: return
    if (r.stabilizations.size <= 1 && !r.ois) return
    var open by remember { mutableStateOf(false) }
    val eis = state.stabilization.takeIf { it in r.stabilizations } ?: VideoStabilization.OFF
    val label = stringResource(stabilizationName(eis)) + if (r.ois && controls.ois) " + " + stringResource(R.string.stab_ois_short) else ""
    Box {
        QuickChip(
            label,
            onClick = { open = true },
            enabled = !recording,
            active = open,
            contentDescription = "${stringResource(R.string.stab_title)}: $label"
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            VideoStabilization.entries.filter { it in r.stabilizations }.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(stabilizationName(mode)) + if (mode == eis) " ✓" else "") },
                    onClick = {
                        open = false
                        wire.setStabilization(mode)
                    }
                )
            }
            if (r.ois) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (controls.ois) R.string.stab_ois_on else R.string.stab_ois_off)) },
                    onClick = {
                        open = false
                        wire.setOis(!controls.ois)
                    }
                )
            }
        }
    }
}

private fun stabilizationName(mode: VideoStabilization): Int = when (mode) {
    VideoStabilization.OFF -> R.string.stab_off
    VideoStabilization.ON -> R.string.stab_eis
    VideoStabilization.PREVIEW -> R.string.stab_preview
}

/**
 * Second-camera quick chip (D59, D62), shown when the experimental second camera is on: the pairs the phone reports,
 * named after their lenses, automatic first; blocked pairs are greyed with the reason.
 */
@Composable
internal fun SecondCameraChip(multicam: MulticamWire, lenses: LensWire, recording: Boolean) {
    val state by multicam.state.collectAsStateWithLifecycle()
    val lensState by lenses.state.collectAsStateWithLifecycle()
    if (!state.concurrentEnabled || state.pairs.isEmpty()) return
    val catalog = lensState.catalog
    var open by remember { mutableStateOf(false) }
    val chosen = state.pairs.firstOrNull { it.key == state.chosenPair }
    val label = chosen?.let { pairName(it, catalog) } ?: stringResource(R.string.second_auto_short)
    Box {
        QuickChip(
            label,
            onClick = { open = true },
            icon = R.drawable.ic_cam_monitor,
            enabled = !recording,
            active = open,
            contentDescription = "${stringResource(R.string.second_title)}: $label"
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.second_auto) + if (state.chosenPair == null) " ✓" else "") },
                onClick = {
                    open = false
                    multicam.choosePair(null)
                }
            )
            state.pairs.forEach { pair ->
                val reason = pairBlockText(pair, catalog, state.mainMode)
                DropdownMenuItem(
                    text = {
                        Text(pairName(pair, catalog) + (if (pair.key == state.chosenPair) " ✓" else "") + (reason?.let { "\n$it" } ?: ""))
                    },
                    enabled = pair.enabled,
                    onClick = {
                        open = false
                        multicam.choosePair(pair.key)
                    }
                )
            }
        }
    }
}

@Composable
private fun pairName(pair: PairOption, catalog: LensCatalog?): String =
    stringResource(R.string.second_pair, lensName(pair.ids[0], catalog), lensName(pair.ids[1], catalog))

@Composable
private fun pairBlockText(pair: PairOption, catalog: LensCatalog?, mode: String): String? = when (pair.block) {
    null -> null
    PairBlock.OTHER_MAIN_CAMERA -> {
        val names = pair.ids.map { lensName(it, catalog) }
        stringResource(R.string.second_block_other, names.joinToString(" / "))
    }
    PairBlock.REFUSED -> stringResource(R.string.second_block_refused, mode)
}

/** A camera's lens name from the catalogue ("Main", "Ultra-wide", "Front"…), or "Camera 3" when unknown. */
@Composable
internal fun lensName(id: String, catalog: LensCatalog?): String = when (catalog?.kindOf(id)) {
    LensKind.MAIN -> stringResource(R.string.lens_main)
    LensKind.ULTRA_WIDE -> stringResource(R.string.lens_ultra_wide)
    LensKind.CROP -> stringResource(R.string.lens_crop)
    LensKind.TELE -> stringResource(R.string.lens_tele, LensCatalog.formatFactor(catalog.factorOf(id)))
    LensKind.FRONT -> stringResource(R.string.lens_front_name)
    null -> stringResource(R.string.lens_camera, id)
}
