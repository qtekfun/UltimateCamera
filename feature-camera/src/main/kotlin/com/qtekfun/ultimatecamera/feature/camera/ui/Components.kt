package com.qtekfun.ultimatecamera.feature.camera.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatecamera.feature.camera.R
import kotlin.math.log10

/** How a readout should be read: normal, attention (amber) or problem (red). */
enum class Tone { NORMAL, OK, WARNING, ALERT }

@Composable
private fun Tone.color(): Color = when (this) {
    Tone.NORMAL -> CameraTheme.colors.content
    Tone.OK -> CameraTheme.colors.ok
    Tone.WARNING -> CameraTheme.colors.warning
    Tone.ALERT -> CameraTheme.colors.rec
}

/** The base overlay surface: a flat translucent scrim with a hairline edge, legible over any image. */
@Composable
fun ScrimSurface(modifier: Modifier = Modifier, shape: Shape = CameraShapes.surface, strong: Boolean = false, content: @Composable () -> Unit) {
    val colors = CameraTheme.colors
    Box(
        modifier
            .clip(shape)
            .background(if (strong) colors.scrimStrong else colors.scrim)
            .border(CameraTheme.hairlineBorder, shape)
    ) { content() }
}

/** A 48 dp round icon button on a scrim. [selected] fills it with the dynamic accent (an open panel, a lock on). */
@Composable
fun CameraIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false
) {
    val colors = CameraTheme.colors
    val background by animateColorAsState(if (selected) colors.accent else colors.scrim, label = "iconButtonBg")
    val tint = when {
        !enabled -> colors.contentDisabled
        selected -> colors.onAccent
        else -> colors.content
    }
    Box(
        modifier
            .size(CameraDimens.touch)
            .clip(CircleShape)
            .background(background)
            .border(CameraTheme.hairlineBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(CameraDimens.icon))
    }
}

/**
 * A quick-setting chip: icon + short value ("HEVC", "4K30 10-bit", "Rec.709"). 48 dp tall pill; [active] uses the
 * accent (a tool that is on, a panel that is open).
 */
@Composable
fun QuickChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    contentDescription: String? = null
) {
    val colors = CameraTheme.colors
    val background by animateColorAsState(if (active) colors.accent else colors.scrim, label = "chipBg")
    val content = when {
        !enabled -> colors.contentDisabled
        active -> colors.onAccent
        else -> colors.content
    }
    Row(
        modifier
            .heightIn(min = CameraDimens.touch)
            .clip(CameraShapes.pill)
            .background(background)
            .border(CameraTheme.hairlineBorder, CameraShapes.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let { Icon(painterResource(it), contentDescription = null, tint = content, modifier = Modifier.size(18.dp)) }
        Text(label, style = CameraTheme.type.value, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small outlined badge for the status strip: HEVC, 10-BIT, 4K, 30P. */
@Composable
fun StatusBadge(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.NORMAL) {
    val color = tone.color()
    Text(
        text,
        style = CameraTheme.type.label,
        color = color,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.55f), CameraShapes.badge)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** An icon + value pair for the status strip (storage left, battery, temperature); tappable when [onClick] is set. */
@Composable
fun StatusReadout(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NORMAL,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    val color = tone.color()
    val tap = onClick?.let {
        Modifier.heightIn(min = CameraDimens.touch).clip(CameraShapes.badge).clickable(role = Role.Button, onClick = it).padding(horizontal = 4.dp)
    }
    val description = contentDescription?.let { Modifier.semantics(mergeDescendants = true) { this.contentDescription = it } }
    Row(
        modifier.then(tap ?: Modifier).then(description ?: Modifier),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = CameraTheme.type.label, color = color, maxLines = 1)
    }
}

/** The red REC dot; it breathes slowly while recording and is a grey ring while standing by. */
@Composable
fun RecDot(recording: Boolean, modifier: Modifier = Modifier) {
    val colors = CameraTheme.colors
    if (recording) {
        val pulse by rememberInfiniteTransition(label = "recPulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
            label = "recAlpha"
        )
        Box(modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(colors.rec))
    } else {
        Box(modifier.size(10.dp).border(1.5.dp, colors.contentMuted, CircleShape))
    }
}

/**
 * The top status strip: REC dot and timecode, then mode badges, then [trailing] readouts (storage, battery, thermal).
 * One pill so it reads as a single instrument line, like the top bar of a cinema camera.
 */
@Composable
fun StatusStrip(
    recording: Boolean,
    timecode: String,
    modifier: Modifier = Modifier,
    badges: List<String> = emptyList(),
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val recLabel = stringResource(R.string.ui_rec)
    ScrimSurface(modifier, shape = CameraShapes.pill) {
        Row(
            Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.semantics(mergeDescendants = true) {
                    if (recording) contentDescription = "$recLabel $timecode"
                },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RecDot(recording)
                Text(
                    timecode,
                    style = CameraTheme.type.timecode,
                    color = if (recording) CameraTheme.colors.content else CameraTheme.colors.contentMuted
                )
            }
            badges.forEach { StatusBadge(it) }
            trailing()
        }
    }
}

/**
 * The record button. The inner shape morphs from a red circle (standing by) to a smaller rounded square (recording),
 * like every pro camera app; in photo mode it is a white shutter disc.
 */
@Composable
fun RecordButton(
    recording: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    photo: Boolean = false,
    size: Dp = CameraDimens.rec
) {
    val colors = CameraTheme.colors
    val description = stringResource(
        when {
            photo -> R.string.ui_take_photo
            recording -> R.string.ui_stop_recording
            else -> R.string.ui_start_recording
        }
    )
    val inner by animateDpAsState(if (recording) size * 0.40f else size * 0.78f, label = "recInner")
    val corner by animateDpAsState(if (recording) 8.dp else size * 0.39f, label = "recCorner")
    val fill by animateColorAsState(
        when {
            !enabled -> colors.contentDisabled
            photo -> colors.content
            else -> colors.rec
        },
        label = "recFill"
    )
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.scrim)
            .border(4.dp, colors.content.copy(alpha = if (enabled) 1f else 0.4f), CircleShape)
            .then(
                if (photo) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
                } else {
                    Modifier.toggleable(value = recording, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
                }
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(fill))
    }
}

/**
 * The discreet Video/Photo toggle (owner: the mode must not take space from image settings). One 48 dp icon button
 * showing the current mode; tapping switches to the other one. Its description names the mode it switches to.
 */
@Composable
fun ModeToggle(photo: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val target = stringResource(if (photo) R.string.ui_switch_to_video else R.string.ui_switch_to_photo)
    CameraIconButton(
        icon = if (photo) R.drawable.ic_cam_photo else R.drawable.ic_cam_video,
        contentDescription = target,
        onClick = onToggle,
        modifier = modifier,
        enabled = enabled
    )
}

/** AE-L / AF-L style lock toggle: amber fill with a lock glyph while locked, outlined while free. */
@Composable
fun LockChip(label: String, locked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = CameraTheme.colors
    val background by animateColorAsState(if (locked) colors.warning else Color.Transparent, label = "lockBg")
    val content = when {
        !enabled -> colors.contentDisabled
        locked -> Color.Black
        else -> colors.content
    }
    Row(
        modifier
            .heightIn(min = CameraDimens.touch)
            .toggleable(value = locked, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .border(1.dp, if (locked) colors.warning else colors.hairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(if (locked) R.drawable.ic_cam_lock else R.drawable.ic_cam_lock_open),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp)
            )
            Text(label, style = CameraTheme.type.value, color = content, maxLines = 1)
        }
    }
}

/** Video / Photo segmented pill. The selected segment carries the accent; both segments are 48 dp tall. */
@Composable
fun ModeSwitch(photo: Boolean, onSelect: (photo: Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ScrimSurface(modifier, shape = CameraShapes.pill) {
        Row(Modifier.padding(3.dp).selectableGroup()) {
            ModeSegment(R.drawable.ic_cam_video, stringResource(R.string.ui_mode_video), selected = !photo, enabled = enabled) { onSelect(false) }
            ModeSegment(R.drawable.ic_cam_photo, stringResource(R.string.ui_mode_photo), selected = photo, enabled = enabled) { onSelect(true) }
        }
    }
}

@Composable
private fun ModeSegment(@DrawableRes icon: Int, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = CameraTheme.colors
    val background by animateColorAsState(if (selected) colors.accent else Color.Transparent, label = "segmentBg")
    val content = when {
        selected -> colors.onAccent
        enabled -> colors.content
        else -> colors.contentDisabled
    }
    Row(
        Modifier
            .heightIn(min = 42.dp)
            .clip(CameraShapes.pill)
            .background(background)
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(label, style = CameraTheme.type.value, color = content)
    }
}

/**
 * Vertical peak meters (L, R) with a dB scale at 0, -6, -12, -20 and -40 dBFS. Green below -12, amber up to -6 (the
 * target zone for dialogue peaks), red above; the whole meter turns red when a channel clipped recently.
 */
@Composable
fun AudioMeterBars(left: Float, right: Float, clip: Boolean, modifier: Modifier = Modifier, height: Dp = 120.dp) {
    val colors = CameraTheme.colors
    val description = stringResource(R.string.ui_audio_meter, peakToDb(left).toInt(), peakToDb(right).toInt())
    ScrimSurface(modifier.semantics { contentDescription = description }, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.height(height).width(20.dp)) {
                METER_TICKS.forEach { db ->
                    Text(
                        "$db",
                        style = CameraTheme.type.label,
                        color = colors.contentMuted,
                        modifier = Modifier.align(Alignment.TopEnd).offset(y = height * (1f - meterFraction(db.toFloat())) - 7.dp)
                    )
                }
            }
            listOf(left, right).forEach { level ->
                Canvas(Modifier.width(8.dp).height(height)) {
                    val db = peakToDb(level)
                    val fraction = meterFraction(db)
                    drawRect(colors.scrimRaised)
                    val segment = 3.dp.toPx()
                    val gap = 1.dp.toPx()
                    var y = size.height
                    val top = size.height * (1f - fraction)
                    while (y - segment >= top) {
                        val at = 1f - (y - segment / 2) / size.height
                        val color = when {
                            clip || at > RED_FROM -> colors.rec
                            at > AMBER_FROM -> colors.warning
                            else -> colors.ok
                        }
                        drawRect(color, topLeft = Offset(0f, y - segment), size = Size(size.width, segment))
                        y -= segment + gap
                    }
                    METER_TICKS.forEach { tick ->
                        val ty = size.height * (1f - meterFraction(tick.toFloat()))
                        drawLine(colors.hairline, Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1f)
                    }
                }
            }
        }
    }
}

/** Toasts for the camera: dark rounded card over the preview instead of the default light snackbar. */
@Composable
fun CameraSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = CameraTheme.colors
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            modifier = Modifier.widthIn(max = 480.dp).padding(horizontal = 16.dp, vertical = 8.dp).border(CameraTheme.hairlineBorder, CameraShapes.surface),
            shape = CameraShapes.surface,
            containerColor = colors.scrimStrong,
            contentColor = colors.content,
            action = data.visuals.actionLabel?.let { label ->
                { TextButton(onClick = data::performAction) { Text(label, style = CameraTheme.type.label, color = colors.content) } }
            }
        ) {
            Text(data.visuals.message, style = CameraTheme.type.body)
        }
    }
}

/** A floating panel (monitor tools, manual controls, LUT picker) with a title row and a close button. */
@Composable
fun CameraPanel(title: String, onClose: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ScrimSurface(modifier, strong = true) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.uppercase(),
                    style = CameraTheme.type.title,
                    color = CameraTheme.colors.content,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                if (onClose != null) {
                    CloseButton(onClose)
                } else {
                    Box(Modifier.height(CameraDimens.touch))
                }
            }
            content()
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    val description = stringResource(R.string.ui_close)
    Box(
        Modifier
            .size(CameraDimens.touch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClose)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(R.drawable.ic_cam_close), contentDescription = null, tint = CameraTheme.colors.contentMuted, modifier = Modifier.size(18.dp))
    }
}

/** A labelled group inside a [CameraPanel]. */
@Composable
fun PanelSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), style = CameraTheme.type.label, color = CameraTheme.colors.contentMuted, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** A selectable option inside a panel: 40 dp tall pill (48 dp touch area), accent when selected. */
@Composable
fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = CameraTheme.colors
    val background by animateColorAsState(if (selected) colors.accent else colors.scrimRaised, label = "optionBg")
    val content = when {
        !enabled -> colors.contentDisabled
        selected -> colors.onAccent
        else -> colors.content
    }
    Box(
        modifier
            .heightIn(min = CameraDimens.touch)
            .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = CameraTheme.type.value,
            color = content,
            maxLines = 1,
            modifier = Modifier
                .clip(CameraShapes.pill)
                .background(background)
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

/** A labelled numeric readout (ISO / 800, SHUTTER / 1/50) that opens its control when tapped. */
@Composable
fun ValueReadout(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    locked: Boolean = false,
    enabled: Boolean = true
) {
    val colors = CameraTheme.colors
    val background by animateColorAsState(if (active) colors.accent else Color.Transparent, label = "readoutBg")
    val content = when {
        !enabled -> colors.contentDisabled
        active -> colors.onAccent
        else -> colors.content
    }
    Column(
        modifier
            .heightIn(min = CameraDimens.touch)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), style = CameraTheme.type.label, color = if (active) content else colors.contentMuted)
            if (locked) {
                Icon(
                    painterResource(R.drawable.ic_cam_lock),
                    contentDescription = stringResource(R.string.ui_locked),
                    tint = colors.warning,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
        Text(value, style = CameraTheme.type.value, color = content, maxLines = 1)
    }
}

/** A thin vertical rule between groups of a control column or strip. */
@Composable
fun StripDivider(vertical: Boolean = true) {
    val color = CameraTheme.colors.hairline
    Box(Modifier.then(if (vertical) Modifier.width(1.dp).height(18.dp) else Modifier.height(1.dp).fillMaxWidth()).background(color))
}

/** `HH:MM:SS:FF` take timecode; frames count at [fps] (non-drop-frame, like a camera's REC run timecode). */
fun formatTimecode(elapsedMs: Long, fps: Int): String {
    val ms = elapsedMs.coerceAtLeast(0)
    val total = ms / MS_PER_SECOND
    val frames = if (fps > 0) (ms % MS_PER_SECOND * fps / MS_PER_SECOND).toInt() else 0
    return "%02d:%02d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60, frames)
}

/** Linear peak (0..1) to dBFS, floored at [MIN_DB]. */
fun peakToDb(level: Float): Float = if (level <= 0f) MIN_DB else (20 * log10(level)).coerceIn(MIN_DB, 0f)

/** Height fraction of the meter for [db]: a log-ish scale that gives the top 20 dB (where speech lives) half the bar. */
fun meterFraction(db: Float): Float {
    val d = db.coerceIn(MIN_DB, 0f)
    return if (d >= -20f) 0.5f + 0.5f * (1f + d / 20f) else 0.5f * (1f - (d + 20f) / (MIN_DB + 20f))
}

private const val MS_PER_SECOND = 1000L
private const val MIN_DB = -60f
private val METER_TICKS = listOf(0, -6, -12, -20, -40)
private val RED_FROM = meterFraction(-6f)
private val AMBER_FROM = meterFraction(-12f)
