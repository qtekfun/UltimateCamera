package com.qtekfun.ultimatecamera.feature.camera

import androidx.annotation.StringRes
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitBlock
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitNotice
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitOptions
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitStatus
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The video Blur chip (D89): [on] for the session (off by default), the aperture slider [aperture] (0 = off, then
 * [Bokeh.STOPS]). The focus is the camera's tap point (the reticle). The decisions of [VideoPortraitChip], pure.
 */
data class VideoPortraitUi(val on: Boolean = false, val aperture: Int = PortraitUi.DEFAULT_APERTURE) {
    val fNumber: Float get() = Bokeh.fNumber(aperture)

    /** Turning the blur on or off changes the session (depth outputs); the aperture does not. */
    fun reopens(after: VideoPortraitUi): Boolean = on != after.on

    /** What the chip says. */
    fun label(status: VideoPortraitStatus): ChipLabel = when {
        !on -> ChipLabel.OFF
        status.source != null && status.lowered -> ChipLabel.LOWERED
        status.source != null -> ChipLabel.ON
        else -> ChipLabel.UNAVAILABLE
    }

    enum class ChipLabel { OFF, ON, LOWERED, UNAVAILABLE }

    companion object {
        /** Shown only with Settings › Recording › Background blur in video on and a camera with a depth source for video. */
        fun chipShown(status: VideoPortraitStatus): Boolean = status.offered

        @StringRes
        fun blockText(block: VideoPortraitBlock): Int = when (block) {
            VideoPortraitBlock.TEN_BIT -> R.string.video_portrait_block_ten_bit
            VideoPortraitBlock.DIRECT -> R.string.video_portrait_block_direct
            VideoPortraitBlock.FRAME_RATE -> R.string.video_portrait_block_frame_rate
            VideoPortraitBlock.SIZE -> R.string.video_portrait_block_size
            VideoPortraitBlock.DEPTH_TOO_SLOW -> R.string.video_portrait_block_depth_slow
            VideoPortraitBlock.SESSION_REFUSED -> R.string.video_portrait_block_refused
            VideoPortraitBlock.NO_DEPTH_FRAMES -> R.string.video_portrait_block_no_depth
            VideoPortraitBlock.TOO_SLOW -> R.string.video_portrait_block_too_slow
            VideoPortraitBlock.GPU_FAILED -> R.string.video_portrait_block_gpu
        }

        /** The snackbar text of a [notice]: its string and, for one that turned the blur off, the reason's. */
        fun noticeText(notice: VideoPortraitNotice): Pair<Int, Int?> = when (notice) {
            is VideoPortraitNotice.Lowered -> R.string.video_portrait_notice_lowered to null
            is VideoPortraitNotice.TurnedOff -> R.string.video_portrait_notice_off to blockText(notice.reason)
        }
    }
}

/**
 * Wires the Blur chip to the controller's [VideoPortraitWire] (D89): the settings [enabled] and [experimental] go to its
 * options (a change reopens the camera through [reopen], which the caller ignores when no video camera is open), the
 * chip's on/off reopens it, the aperture applies at once, and the wire's notices become snackbars ([onNotice]).
 */
class VideoPortraitControl(
    private val wire: VideoPortraitWire,
    enabled: Flow<Boolean>?,
    experimental: Flow<Boolean>?,
    scope: CoroutineScope,
    private val reopen: () -> Unit,
    onNotice: (VideoPortraitNotice) -> Unit
) {
    private val _ui = MutableStateFlow(VideoPortraitUi())
    val ui: StateFlow<VideoPortraitUi> = _ui.asStateFlow()

    /** What the open session does with the blur. */
    val status: StateFlow<VideoPortraitStatus> get() = wire.status

    init {
        wire.fNumber = _ui.value.fNumber
        val prefs = combine(enabled ?: flowOf(false), experimental ?: flowOf(false)) { e, x -> e to x }
        scope.launch {
            prefs.collect { (e, x) -> wire.options = options(wire.options, e, x) }
        }
        // A later change (the user back from Settings with the camera kept open) needs the session made again.
        scope.launch { prefs.drop(1).collect { reopen() } }
        scope.launch { wire.notices.collect(onNotice) }
    }

    fun set(next: VideoPortraitUi) {
        val before = _ui.value
        if (before == next) return
        _ui.value = next
        wire.fNumber = next.fNumber
        wire.options = wire.options.copy(on = next.on)
        if (before.reopens(next)) reopen()
    }

    companion object {
        fun options(current: VideoPortraitOptions, enabled: Boolean, experimental: Boolean): VideoPortraitOptions =
            current.copy(enabled = enabled, experimental = experimental)
    }
}
