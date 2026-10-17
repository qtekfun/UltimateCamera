package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatecamera.core.camera.CameraControls
import com.qtekfun.ultimatecamera.core.camera.ControlMath
import com.qtekfun.ultimatecamera.core.camera.ControlRanges
import com.qtekfun.ultimatecamera.core.camera.LiveExposure
import com.qtekfun.ultimatecamera.core.camera.WhiteBalance
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import kotlin.math.roundToInt

private val PANEL_SCRIM = Color(0x99000000)
private val LOCK_YELLOW = Color(0xFFFFC107)

private enum class Sheet { ISO, SHUTTER, WB, FOCUS, EV }

/**
 * Manual controls over the preview (REQ-CAM-002): tap to focus and meter, long press to lock AE/AF there, and a bottom
 * bar of chips (ISO, shutter, WB, focus, EV, AE-L, AF-L) each opening its slider. Values shown are the live ones in auto.
 * Place it right after the preview so the other controls stay on top of its touch layer.
 */
@Composable
internal fun ManualControlsLayer(manual: ManualControls, profile: RecordingProfile?) {
    val controls by manual.controls.collectAsStateWithLifecycle()
    val ranges by manual.ranges.collectAsStateWithLifecycle()
    val live by manual.live.collectAsStateWithLifecycle()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var open by remember { mutableStateOf<Sheet?>(null) }
    val r = ranges
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(manual) {
                detectTapGestures(
                    onTap = { manual.meterAt(it.x, it.y, size.width, size.height, lock = false) },
                    onLongPress = { manual.meterAt(it.x, it.y, size.width, size.height, lock = true) },
                    onDoubleTap = { manual.clearPoint() }
                )
            }
    ) {
        if (profile != null) FocusReticle(controls, profile, viewSize, manual.previewRotation)
        if (r != null && profile != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                open?.let { Sheet(it, controls, r, live, profile.fps, manual::set) }
                ChipBar(controls, r, live, open, onOpen = { open = if (open == it) null else it }, onSet = manual::set)
            }
        }
    }
}

@Composable
private fun FocusReticle(controls: CameraControls, profile: RecordingProfile, viewSize: IntSize, rotation: Int) {
    val point = controls.meteringPoint ?: return
    if (viewSize.width == 0) return
    val (x, y) = ControlMath.imageToPreview(point, viewSize.width, viewSize.height, profile.width, profile.height, rotation)
    val color = if (controls.afLock || controls.aeLock) LOCK_YELLOW else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val side = 72.dp.toPx()
        drawRect(color, topLeft = Offset(x - side / 2, y - side / 2), size = Size(side, side), style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun ChipBar(c: CameraControls, r: ControlRanges, live: LiveExposure, open: Sheet?, onOpen: (Sheet) -> Unit, onSet: (CameraControls) -> Unit) {
    val auto = stringResource(R.string.ctl_auto_short)
    fun shown(manual: Boolean, set: String, now: String?) = if (manual) set else now?.let { "$it $auto" } ?: auto
    val iso = shown(c.manualExposure, c.iso.toString(), live.iso?.toString())
    val shutter = shown(c.manualExposure, ControlRanges.formatShutter(c.exposureTimeNs), live.exposureTimeNs?.let(ControlRanges::formatShutter))
    val focus = shown(c.manualFocus, ControlRanges.formatFocus(c.focusDiopters), live.focusDiopters?.let(ControlRanges::formatFocus))
    val wb = if (c.whiteBalance == WhiteBalance.MANUAL) stringResource(R.string.ctl_kelvin, c.kelvin) else stringResource(wbLabel(c.whiteBalance))
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(PANEL_SCRIM).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ValueChip(stringResource(R.string.ctl_iso), iso, open == Sheet.ISO) { onOpen(Sheet.ISO) }
        ValueChip(stringResource(R.string.ctl_shutter), shutter, open == Sheet.SHUTTER) { onOpen(Sheet.SHUTTER) }
        ValueChip(stringResource(R.string.ctl_wb), wb, open == Sheet.WB) { onOpen(Sheet.WB) }
        ValueChip(stringResource(R.string.ctl_focus), focus, open == Sheet.FOCUS) { onOpen(Sheet.FOCUS) }
        ValueChip(stringResource(R.string.ctl_ev), ControlRanges.formatEv(r.ev(c.exposureCompensation)), open == Sheet.EV) { onOpen(Sheet.EV) }
        LockChip(stringResource(R.string.ctl_ae_lock), c.aeLock || live.aeLocked, enabled = r.aeLock && !c.manualExposure) { onSet(c.copy(aeLock = !c.aeLock)) }
        LockChip(stringResource(R.string.ctl_af_lock), c.afLock, enabled = r.minFocusDiopters > 0f && !c.manualFocus) { onSet(c.copy(afLock = !c.afLock)) }
    }
}

@Composable
private fun ValueChip(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.labelMedium)
            }
        },
        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White)
    )
}

@Composable
private fun LockChip(label: String, locked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = locked,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = LOCK_YELLOW, selectedLabelColor = Color.Black)
    )
}

@Composable
private fun Sheet(sheet: Sheet, c: CameraControls, r: ControlRanges, live: LiveExposure, fps: Int, onSet: (CameraControls) -> Unit) {
    Column(
        Modifier.width(420.dp).clip(RoundedCornerShape(12.dp)).background(PANEL_SCRIM).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (sheet) {
            Sheet.ISO -> IsoSheet(c, r, live, fps, onSet)
            Sheet.SHUTTER -> ShutterSheet(c, r, live, fps, onSet)
            Sheet.WB -> WbSheet(c, r, onSet)
            Sheet.FOCUS -> FocusSheet(c, r, live, onSet)
            Sheet.EV -> EvSheet(c, r, onSet)
        }
    }
}

@Composable
private fun IsoSheet(c: CameraControls, r: ControlRanges, live: LiveExposure, fps: Int, onSet: (CameraControls) -> Unit) {
    val stops = r.isoStops()
    if (stops.isEmpty()) return Unavailable()
    val current = if (c.manualExposure) c.iso else live.iso ?: c.iso
    StopRow(
        value = current.toString(),
        auto = !c.manualExposure,
        onAuto = { onSet(c.copy(manualExposure = false)) },
        count = stops.size,
        index = ControlRanges.nearestIndex(stops, current.toDouble()),
        onIndex = { onSet(ManualRules.toManualExposure(c, live, fps).copy(iso = stops[it])) }
    )
}

@Composable
private fun ShutterSheet(c: CameraControls, r: ControlRanges, live: LiveExposure, fps: Int, onSet: (CameraControls) -> Unit) {
    val stops = r.shutterStops(fps)
    if (stops.isEmpty()) return Unavailable()
    val current = if (c.manualExposure) c.exposureTimeNs else live.exposureTimeNs ?: c.exposureTimeNs
    StopRow(
        value = ControlRanges.formatShutter(current),
        auto = !c.manualExposure,
        onAuto = { onSet(c.copy(manualExposure = false)) },
        count = stops.size,
        index = ControlRanges.nearestIndex(stops, current.toDouble()),
        onIndex = { onSet(ManualRules.toManualExposure(c, live, fps).copy(exposureTimeNs = stops[it])) }
    ) {
        TextButton(onClick = { onSet(ManualRules.toManualExposure(c, live, fps).copy(exposureTimeNs = ControlMath.shutter180Ns(fps))) }) {
            Text(stringResource(R.string.ctl_shutter_180), color = Color.White)
        }
    }
}

@Composable
private fun EvSheet(c: CameraControls, r: ControlRanges, onSet: (CameraControls) -> Unit) {
    if (r.evRange.first == r.evRange.last) return Unavailable()
    if (c.manualExposure) {
        Text(stringResource(R.string.ctl_ev_needs_auto), color = Color.White, style = MaterialTheme.typography.bodySmall)
        return
    }
    val steps = r.evRange.toList()
    StopRow(
        value = ControlRanges.formatEv(r.ev(c.exposureCompensation)),
        auto = c.exposureCompensation == 0,
        onAuto = { onSet(c.copy(exposureCompensation = 0)) },
        count = steps.size,
        index = steps.indexOf(c.exposureCompensation).coerceAtLeast(0),
        onIndex = { onSet(c.copy(exposureCompensation = steps[it])) },
        autoLabel = R.string.ctl_reset
    )
}

@Composable
private fun FocusSheet(c: CameraControls, r: ControlRanges, live: LiveExposure, onSet: (CameraControls) -> Unit) {
    if (!r.manualFocus) return Unavailable()
    // Slider from infinity (left) to the nearest distance (right), in hundredths of a diopter.
    val max = (r.minFocusDiopters * 100).roundToInt().coerceAtLeast(1)
    val current = if (c.manualFocus) c.focusDiopters else live.focusDiopters ?: c.focusDiopters
    StopRow(
        value = ControlRanges.formatFocus(current),
        auto = !c.manualFocus,
        onAuto = { onSet(c.copy(manualFocus = false)) },
        count = max + 1,
        index = (current * 100).roundToInt().coerceIn(0, max),
        onIndex = { onSet(ManualRules.toManualFocus(c, live).copy(focusDiopters = it / 100f)) }
    )
}

@Composable
private fun WbSheet(c: CameraControls, r: ControlRanges, onSet: (CameraControls) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        WhiteBalance.entries.filter { it in r.whiteBalances }.forEach { wb ->
            FilterChip(
                selected = c.whiteBalance == wb,
                onClick = { onSet(c.copy(whiteBalance = wb)) },
                label = { Text(stringResource(wbLabel(wb)), style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(labelColor = Color.White)
            )
        }
        if (r.kelvinRange != null) {
            FilterChip(
                selected = c.whiteBalance == WhiteBalance.MANUAL,
                onClick = { onSet(c.copy(whiteBalance = WhiteBalance.MANUAL)) },
                label = { Text(stringResource(R.string.ctl_wb_kelvin), style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(labelColor = Color.White)
            )
        }
        if (r.awbLock) {
            LockChip(stringResource(R.string.ctl_awb_lock), c.awbLock, enabled = c.whiteBalance != WhiteBalance.MANUAL) { onSet(c.copy(awbLock = !c.awbLock)) }
        }
    }
    val kelvins = r.kelvinRange
    if (c.whiteBalance == WhiteBalance.MANUAL && kelvins != null) {
        val stops = (kelvins.first..kelvins.last step KELVIN_STEP).toList()
        StopRow(
            value = stringResource(R.string.ctl_kelvin, c.kelvin),
            auto = false,
            onAuto = { onSet(c.copy(whiteBalance = WhiteBalance.AUTO)) },
            count = stops.size,
            index = ControlRanges.nearestIndex(stops, c.kelvin.toDouble()),
            onIndex = { onSet(c.copy(kelvin = stops[it])) }
        )
    }
}

/** Auto button, −, slider over [count] stops, +, and the value; [extra] goes before the value. */
@Composable
private fun StopRow(
    value: String,
    auto: Boolean,
    onAuto: () -> Unit,
    count: Int,
    index: Int,
    onIndex: (Int) -> Unit,
    autoLabel: Int = R.string.ctl_auto,
    extra: @Composable () -> Unit = {}
) {
    val decrease = stringResource(R.string.ctl_decrease)
    val increase = stringResource(R.string.ctl_increase)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        FilterChip(
            selected = auto,
            onClick = onAuto,
            label = { Text(stringResource(autoLabel), style = MaterialTheme.typography.labelSmall) },
            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White)
        )
        TextButton(onClick = { onIndex(ManualRules.step(index, -1, count)) }, modifier = Modifier.semantics { contentDescription = decrease }) {
            Text("−", color = Color.White)
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { v -> v.roundToInt().coerceIn(0, count - 1).takeIf { it != index }?.let(onIndex) },
            valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onIndex(ManualRules.step(index, 1, count)) }, modifier = Modifier.semantics { contentDescription = increase }) {
            Text("+", color = Color.White)
        }
        extra()
        Text(value, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun Unavailable() {
    Text(stringResource(R.string.ctl_unavailable), color = Color.White, style = MaterialTheme.typography.bodySmall)
}

private fun wbLabel(wb: WhiteBalance): Int = when (wb) {
    WhiteBalance.AUTO -> R.string.ctl_wb_auto
    WhiteBalance.DAYLIGHT -> R.string.ctl_wb_daylight
    WhiteBalance.CLOUDY -> R.string.ctl_wb_cloudy
    WhiteBalance.TUNGSTEN -> R.string.ctl_wb_tungsten
    WhiteBalance.FLUORESCENT -> R.string.ctl_wb_fluorescent
    WhiteBalance.MANUAL -> R.string.ctl_wb_kelvin
}

private const val KELVIN_STEP = 100
