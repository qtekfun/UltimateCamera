package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.camera.AuxOutput
import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.camera.DepthSupport
import com.qtekfun.ultimatecamera.core.camera.PixelSize
import com.qtekfun.ultimatecamera.core.camera.PortraitSelector
import com.qtekfun.ultimatecamera.core.camera.VideoDepthRates
import com.qtekfun.ultimatecamera.core.camera.VideoDepthSelector
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthTemporalFilter
import com.qtekfun.ultimatecamera.core.gpu.portrait.FocusSmoother
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurCause
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurLevel

/**
 * Portrait in video for the next open (D89): Settings › Recording › *Background blur in video* ([enabled], off by
 * default), Settings › Experimental ([experimental], for the stereo pair) and the video chip ([on], off by default).
 */
data class VideoPortraitOptions(val enabled: Boolean = false, val experimental: Boolean = false, val on: Boolean = false)

/** Why portrait video does not run (or stopped) with the chip on (D89). */
enum class VideoPortraitBlock {
    /** 10-bit HLG: the blur pass is 8-bit and blends a non-linear HDR signal; not offered (D89). */
    TEN_BIT,

    /** Direct recording (D71) has no GPU pass to blur in. */
    DIRECT,

    /** Above the frame rate this phone's tier allows ([VideoPortraitRules.maxFps]). */
    FRAME_RATE,

    /** Larger than 4K UHD (the full-size pass would not fit the frame budget). */
    SIZE,

    /** The depth outputs cannot run at the video's frame rate. */
    DEPTH_TOO_SLOW,

    /** The camera refused the video session with the depth outputs. */
    SESSION_REFUSED,

    /** The session opened but the depth outputs stayed silent. */
    NO_DEPTH_FRAMES,

    /** Stepped down to off: the phone could not keep the frame rate with the blur. */
    TOO_SLOW,

    /** The GPU pass failed; the take went on plain. */
    GPU_FAILED
}

/** What portrait video does on the next open (D89). */
sealed interface VideoPortraitVerdict {
    /** The setting is off or the camera has no depth source for video: no chip. */
    data object NotOffered : VideoPortraitVerdict

    /** Offered, the chip is off. */
    data object Off : VideoPortraitVerdict

    data class Blocked(val reason: VideoPortraitBlock) : VideoPortraitVerdict

    /** Run with [source] through [outputs], depth in one request of [depthEvery], the blur starting at [start]. */
    data class Run(val source: DepthSource, val outputs: List<AuxOutput>, val depthEvery: Int, val start: VideoBlurLevel) : VideoPortraitVerdict
}

/** The chip's view of portrait video (D89). */
data class VideoPortraitStatus(
    /** The chip is shown: the setting is on and the camera has a depth source for video. */
    val offered: Boolean = false,
    val on: Boolean = false,
    /** The source in use, null when not running. */
    val source: DepthSource? = null,
    /** The rung the session started on, and the one in use (lower after a step down). */
    val start: VideoBlurLevel = VideoBlurLevel.OFF,
    val level: VideoBlurLevel = VideoBlurLevel.OFF,
    val block: VideoPortraitBlock? = null
) {
    /** Running, but on a lighter rung than it started on. */
    val lowered: Boolean get() = source != null && level != VideoBlurLevel.OFF && level.ordinal > start.ordinal
}

/** A change during a session the user is told about (a translated snackbar, D89). */
sealed interface VideoPortraitNotice {
    /** The blur went down to [level] to keep the frame rate. */
    data class Lowered(val level: VideoBlurLevel) : VideoPortraitNotice

    /** The blur stopped for [reason]; the take goes on plain. */
    data class TurnedOff(val reason: VideoPortraitBlock) : VideoPortraitNotice
}

/** The pure decisions of portrait video (D89). */
object VideoPortraitRules {
    /** 60 fps on capable phones, 30 on modest ones (D68). */
    const val MAX_FPS = 60
    const val MODEST_MAX_FPS = 30
    const val MAX_PIXELS = 3840L * 2160

    /** Depth updates per second aimed at: enough for moving subjects, cheap for the camera and the depth worker. */
    const val DEPTH_HZ = 15
    const val MODEST_DEPTH_HZ = 10
    private const val GATE_PERCENT = 80L
    private const val PERCENT = 100L

    fun maxFps(modest: Boolean): Int = if (modest) MODEST_MAX_FPS else MAX_FPS

    /** Sources a video session can use: ours only (DEPTH16 and, with Experimental, stereo); the maker's extension has no video. */
    fun sources(support: DepthSupport, experimental: Boolean): List<DepthSource> =
        PortraitSelector.available(support, experimental).filter { it != DepthSource.VENDOR_BOKEH }

    /**
     * The verdict for [profile] with [options]: [offered] are the camera's video sources, [usable] those that did not
     * fail on it in this run ([PortraitAvailability]); [rates] say how fast its depth outputs run.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun verdict(
        options: VideoPortraitOptions,
        profile: RecordingProfile,
        offered: List<DepthSource>,
        usable: List<DepthSource>,
        support: DepthSupport,
        rates: VideoDepthRates,
        modest: Boolean
    ): VideoPortraitVerdict {
        if (!options.enabled || offered.isEmpty()) return VideoPortraitVerdict.NotOffered
        if (!options.on) return VideoPortraitVerdict.Off
        block(profile, modest)?.let { return VideoPortraitVerdict.Blocked(it) }
        if (usable.isEmpty()) return VideoPortraitVerdict.Blocked(VideoPortraitBlock.SESSION_REFUSED)
        val video = PixelSize(profile.width, profile.height)
        for (source in usable) {
            val outputs = VideoDepthSelector.outputs(source, support, rates, video, profile.fps) ?: continue
            return VideoPortraitVerdict.Run(
                source,
                outputs,
                depthEvery(profile.fps, modest),
                VideoBlurLevel.start(profile.width, profile.height, profile.fps, modest)
            )
        }
        return VideoPortraitVerdict.Blocked(VideoPortraitBlock.DEPTH_TOO_SLOW)
    }

    /** What in the mode itself rules portrait out, or null. */
    fun block(profile: RecordingProfile, modest: Boolean): VideoPortraitBlock? = when {
        profile.tenBit -> VideoPortraitBlock.TEN_BIT
        profile.isDirect -> VideoPortraitBlock.DIRECT
        profile.fps > maxFps(modest) -> VideoPortraitBlock.FRAME_RATE
        profile.width.toLong() * profile.height > MAX_PIXELS -> VideoPortraitBlock.SIZE
        else -> null
    }

    /** One request in this many fills the depth outputs: about [DEPTH_HZ] (modest [MODEST_DEPTH_HZ]) depth frames a second. */
    fun depthEvery(fps: Int, modest: Boolean): Int {
        val hz = if (modest) MODEST_DEPTH_HZ else DEPTH_HZ
        return ((fps + hz - 1) / hz).coerceAtLeast(1)
    }

    /** The depth worker takes a frame at most this often (a little under the depth cadence, so none is lost to jitter). */
    fun minDepthIntervalNanos(fps: Int, depthEvery: Int): Long = VideoDepthSelector.frameNanos(fps) * depthEvery * GATE_PERCENT / PERCENT

    /** The status a verdict gives before the session runs. */
    fun status(verdict: VideoPortraitVerdict, on: Boolean): VideoPortraitStatus = when (verdict) {
        VideoPortraitVerdict.NotOffered -> VideoPortraitStatus()
        VideoPortraitVerdict.Off -> VideoPortraitStatus(offered = true)
        is VideoPortraitVerdict.Blocked -> VideoPortraitStatus(offered = true, on = on, block = verdict.reason)
        is VideoPortraitVerdict.Run -> VideoPortraitStatus(offered = true, on = true, source = verdict.source, start = verdict.start, level = verdict.start)
    }

    /** What the user is told when the governor changes the rung for [cause]. */
    fun notice(level: VideoBlurLevel, cause: VideoBlurCause): VideoPortraitNotice = when {
        level != VideoBlurLevel.OFF -> VideoPortraitNotice.Lowered(level)
        cause == VideoBlurCause.GPU_FAILURE -> VideoPortraitNotice.TurnedOff(VideoPortraitBlock.GPU_FAILED)
        else -> VideoPortraitNotice.TurnedOff(VideoPortraitBlock.TOO_SLOW)
    }

    /** The status after the governor moved to [level] for [cause]. */
    fun afterChange(status: VideoPortraitStatus, level: VideoBlurLevel, cause: VideoBlurCause): VideoPortraitStatus = if (level == VideoBlurLevel.OFF) {
        status.copy(source = null, level = level, block = (notice(level, cause) as VideoPortraitNotice.TurnedOff).reason)
    } else {
        status.copy(level = level)
    }
}

/**
 * The depth of portrait video between the camera and the GPU (D89): each live depth map is smoothed in time
 * ([DepthTemporalFilter]), the focus is the depth at the tapped point on the smoothed map, itself smoothed
 * ([FocusSmoother], faster right after a tap). One thread (the depth worker).
 */
class VideoDepthSmoother(private val temporal: DepthTemporalFilter = DepthTemporalFilter(), private val focus: FocusSmoother = FocusSmoother()) {
    /** The next depth for the renderer from a raw [inverse] map and [look]; [newPoint] after a tap. */
    fun next(inverse: Plane, look: PortraitLook, newPoint: Boolean): LiveDepth {
        val smoothed = temporal.apply(inverse)
        val target = Bokeh.focusAt(smoothed, look.focusX, look.focusY)
        return LiveDepth(smoothed, focus.next(target, newPoint), look.fNumber)
    }

    fun reset() {
        temporal.reset()
        focus.reset()
    }
}
