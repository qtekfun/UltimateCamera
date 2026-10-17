package com.qtekfun.ultimatecamera.feature.multicam.monitor

import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.net.control.ChoiceInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody

/** Which of the camera's two looks the monitor's style picker sets (D79): the recorded one or the monitor's. */
enum class StyleTarget { RECORDING, MONITOR }

/** A style preset the camera offers: its [token] at the defaults, the [preset] when this app knows it, and the camera's [label]. */
data class StyleOption(val token: String, val preset: StylePreset?, val label: String)

/**
 * One chip of the picker: the "none"/"auto" chip ([none]), a preset this app knows ([preset], shown in its own
 * language) or one only the camera knows ([cameraLabel]); tapping it sends [command].
 */
data class StyleChip(val none: Boolean, val preset: StylePreset?, val cameraLabel: String?, val selected: Boolean, val command: CommandBody)

/** What the picker shows for one look: its chips, the next-take note, the style the tuner shows (null: no tuner). */
data class StylePanel(val chips: List<StyleChip>, val nextTake: Boolean, val shown: StyleParams?, val notTunable: Boolean)

/**
 * The monitor's style picker (D79), pure and unit-tested: the camera lists its presets in the status, the monitor
 * sends style tokens through the same commands as the other looks ([CommandBody.SelectLut] for the recording,
 * [CommandBody.SetMonitorLook] for the monitor), and the camera's rules apply (a recording change during a take is
 * taken for the next take).
 */
object RemoteStyles {
    /** The camera's presets; presets this app does not know (a newer camera) can be picked but not tuned. */
    fun options(styles: List<ChoiceInfo>): List<StyleOption> = styles.map { StyleOption(it.id, StyleParams.parse(it.id)?.preset, it.label) }

    /** The style the camera uses now for [target], or null when that look is not a style (none, auto, a LUT, Log). */
    fun current(ui: RemoteCameraUi, target: StyleTarget): StyleParams? = StyleParams.parse(token(ui, target))

    /** The token the camera reports for [target]. */
    fun token(ui: RemoteCameraUi, target: StyleTarget): String? = if (target == StyleTarget.RECORDING) ui.lutId else ui.monitorLook

    /** The command that sets [target] to [token] (null: no style, i.e. the camera's own signal or automatic monitor). */
    fun command(target: StyleTarget, token: String?): CommandBody = when (target) {
        StyleTarget.RECORDING -> CommandBody.SelectLut(token)
        StyleTarget.MONITOR -> CommandBody.SetMonitorLook(token)
    }

    fun command(target: StyleTarget, params: StyleParams): CommandBody = command(target, params.token)

    /** True when [option] is the camera's current style for that look (by preset, whatever it was tuned to). */
    fun selected(current: StyleParams?, currentToken: String?, option: StyleOption): Boolean =
        if (option.preset != null) current?.preset == option.preset else currentToken == option.token

    /** A recording style chosen during a take is taken for the next take (the camera keeps the take's look). */
    fun appliesNextTake(target: StyleTarget, recording: Boolean): Boolean = target == StyleTarget.RECORDING && recording

    /**
     * What the recording look chip shows for [token]: a LUT's label from [luts], a style's name ([styleName] of its
     * preset), the raw token when unknown, or null for none.
     */
    fun lookLabel(token: String?, luts: List<ChoiceInfo>, styleName: (StylePreset) -> String): String? {
        if (token == null) return null
        luts.firstOrNull { it.id == token }?.let { return it.label }
        return StyleParams.parse(token)?.let { styleName(it.preset) } ?: token
    }

    /** The picker for [target] of the camera [ui], with the user's unconfirmed [draft] (if any) in the tuner. */
    fun panel(ui: RemoteCameraUi, target: StyleTarget, draft: StyleParams?): StylePanel {
        val current = current(ui, target)
        val token = token(ui, target)
        val options = options(ui.styles)
        val none =
            StyleChip(none = true, preset = null, cameraLabel = null, selected = current == null && token == null, command = command(target, null as String?))
        val chips = listOf(none) + options.map { StyleChip(false, it.preset, it.label, selected(current, token, it), command(target, it.token)) }
        val shown = draft ?: current
        val notTunable = shown == null && token != null && options.any { it.token == token && it.preset == null }
        return StylePanel(chips, appliesNextTake(target, ui.rec.showsRecording), shown, notTunable)
    }

    /**
     * What the tuner shows: the user's [draft] while it has not been confirmed by the camera, else the camera's
     * [current] style. Returns the draft to keep (null once the camera reports it).
     */
    fun settle(draft: StyleParams?, current: StyleParams?): StyleParams? = draft?.takeIf { it != current }
}
