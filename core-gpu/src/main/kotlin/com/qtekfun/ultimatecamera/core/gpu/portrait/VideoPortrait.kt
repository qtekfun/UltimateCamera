package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import kotlin.math.abs

/**
 * Quality rungs of the background blur in video (D89, REQ-PHO-005): the blur runs on a reduced copy [width] pixels wide
 * (at most the video's width) with a disc of [samples] taps, and is upsampled edge-aware into the full-size frame.
 * [OFF] records the plain picture. The rungs only go down during a session ([lower]); a reopen starts again.
 */
enum class VideoBlurLevel(val width: Int, val samples: Int) {
    HIGH(960, 48),
    MEDIUM(640, 32),
    LOW(426, 16),
    OFF(0, 0);

    /** The next lighter rung; [OFF] stays off. */
    fun lower(): VideoBlurLevel = entries[(ordinal + 1).coerceAtMost(OFF.ordinal)]

    companion object {
        /**
         * The rung a video of [width] x [height] at [fps] starts on: modest phones (D68) start low; capable ones get
         * [HIGH] up to 1080p30, [MEDIUM] up to 4K30 or 1080p60, [LOW] above.
         */
        fun start(width: Int, height: Int, fps: Int, modest: Boolean): VideoBlurLevel {
            val pixelRate = width.toLong() * height * fps
            return when {
                modest -> LOW
                pixelRate <= FULL_HD_30 -> HIGH
                pixelRate <= UHD_30 -> MEDIUM
                else -> LOW
            }
        }

        private const val FULL_HD_30 = 1920L * 1080 * 30
        private const val UHD_30 = 3840L * 2160 * 30
    }
}

/**
 * Sizes of the reduced blur pass of a [VideoBlurLevel] for a [videoWidth] x [videoHeight] take (sensor frame): the
 * copy is [width] x [height] (even, the video's aspect), the gather takes [samples] taps and its radius is bound by
 * [maxRadius], the capture's bound scaled to the copy's width so photo and video look alike.
 */
data class VideoPortraitPlan(val width: Int, val height: Int, val samples: Int, val maxRadius: Float) {
    companion object {
        /** Null for [VideoBlurLevel.OFF]. */
        fun of(level: VideoBlurLevel, videoWidth: Int, videoHeight: Int): VideoPortraitPlan? {
            if (level == VideoBlurLevel.OFF) return null
            require(videoWidth > 0 && videoHeight > 0) { "video size" }
            val width = minOf(level.width, videoWidth).coerceAtLeast(2) and 1.inv()
            val height = ((width.toLong() * videoHeight + videoWidth / 2) / videoWidth).toInt().coerceAtLeast(2) and 1.inv()
            return VideoPortraitPlan(width, height, level.samples, DepthBlur.MAX_RADIUS * width / LivePortraitPlan.CAPTURE_WIDTH)
        }
    }
}

/** Why the blur went down a rung or off during a session (D89). */
enum class VideoBlurCause { SLOW_FRAMES, DROPPED_FRAMES, GPU_FAILURE }

/**
 * Keeps the video blur inside the frame budget (D89): the take must keep its frame rate, so the blur gives way first.
 * Over a window of [WINDOW] encoded frames, more than [SLOW_LIMIT] frames whose GL work took longer than [BUDGET_PERCENT]
 * of the frame interval, or any dropped frame, step the blur down one rung ([VideoBlurLevel.lower], down to off). After
 * a change (and at the start of each take, [restart]) the first [WARMUP] frames are not judged: new targets and the
 * first draw cost once. It never steps up during a session: a phone that could not hold a rung once will not hold it
 * better hot. Pure; GL thread only.
 */
class VideoBlurGovernor(start: VideoBlurLevel, frameIntervalNanos: Long) {
    private val budgetNanos = frameIntervalNanos * BUDGET_PERCENT / PERCENT

    init {
        require(frameIntervalNanos > 0) { "frame interval" }
    }

    var level: VideoBlurLevel = start
        private set

    /** Why the last step down happened (null before any). */
    var cause: VideoBlurCause? = null
        private set

    private var frames = 0
    private var slow = 0
    private var warmup = WARMUP

    /** A new take: the window and warm-up start again at the current rung. */
    fun restart() {
        frames = 0
        slow = 0
        warmup = WARMUP
    }

    /**
     * One encoded frame with the blur on: [renderNanos] of GL work and [droppedFrames] newly missing camera frames.
     * True when the rung changed (read [level] and [cause]).
     */
    fun record(renderNanos: Long, droppedFrames: Int): Boolean {
        if (level == VideoBlurLevel.OFF) return false
        if (warmup > 0) {
            warmup--
            return false
        }
        if (droppedFrames > 0) return stepDown(VideoBlurCause.DROPPED_FRAMES)
        frames++
        if (renderNanos > budgetNanos) slow++
        if (slow > SLOW_LIMIT) return stepDown(VideoBlurCause.SLOW_FRAMES)
        if (frames >= WINDOW) {
            frames = 0
            slow = 0
        }
        return false
    }

    /** The GPU pass failed: off for the session. */
    fun fail(): Boolean {
        if (level == VideoBlurLevel.OFF) return false
        level = VideoBlurLevel.OFF
        cause = VideoBlurCause.GPU_FAILURE
        return true
    }

    private fun stepDown(why: VideoBlurCause): Boolean {
        level = level.lower()
        cause = why
        restart()
        return true
    }

    companion object {
        const val WINDOW = 30
        const val SLOW_LIMIT = 3
        const val WARMUP = 5
        const val BUDGET_PERCENT = 75L
        private const val PERCENT = 100L
    }
}

/**
 * Temporal smoothing of the live inverse depth (D89): depth maps arrive a few times a second and, especially from stereo
 * block matching, jitter from one to the next, which in video shows as a background that breathes. Each pixel follows
 * the new value by [slowAlpha] when it moved by less than [jumpDioptres] (noise) and by [fastAlpha] when it moved more
 * (something really moved, so no ghost trails); a pixel that loses its depth keeps the last value for [holdUpdates]
 * updates, then has none. A size change starts afresh. Returns a new plane each time (handed to the GL thread as is).
 * One thread (the depth worker).
 */
class DepthTemporalFilter(
    private val slowAlpha: Float = SLOW_ALPHA,
    private val fastAlpha: Float = FAST_ALPHA,
    private val jumpDioptres: Float = JUMP_DIOPTRES,
    private val holdUpdates: Int = HOLD_UPDATES
) {
    private var previous: Plane? = null
    private var missing = IntArray(0)

    fun apply(input: Plane): Plane {
        val prev = previous
        if (prev == null || prev.width != input.width || prev.height != input.height) {
            missing = IntArray(input.data.size)
            return Plane(input.width, input.height, input.data.copyOf()).also { previous = it }
        }
        val out = FloatArray(input.data.size)
        for (i in out.indices) out[i] = pixel(prev.data[i], input.data[i], i)
        return Plane(input.width, input.height, out).also { previous = it }
    }

    private fun pixel(old: Float, new: Float, i: Int): Float {
        val oldValid = old > Bokeh.INVALID
        if (new <= Bokeh.INVALID) {
            if (!oldValid) return Bokeh.INVALID
            missing[i]++
            return if (missing[i] > holdUpdates) Bokeh.INVALID else old
        }
        missing[i] = 0
        if (!oldValid) return new
        val delta = new - old
        return old + delta * if (abs(delta) > jumpDioptres) fastAlpha else slowAlpha
    }

    /** Forgets the history (a new session or camera). */
    fun reset() {
        previous = null
    }

    companion object {
        const val SLOW_ALPHA = 0.35f
        const val FAST_ALPHA = 0.8f
        const val JUMP_DIOPTRES = 0.5f
        const val HOLD_UPDATES = 3
    }
}

/**
 * Temporal smoothing of the focus (D89): the focus is the depth at the tapped point, which jitters a little from one
 * depth map to the next. Changes under [deadband] dioptres are ignored (no breathing); larger ones are followed by
 * [settleAlpha] per update. A new tap ([next] with `newPoint`) racks focus faster ([rackAlpha] for [rackUpdates]
 * updates), like a focus puller; a point without depth keeps the last focus. Pure; one thread.
 */
class FocusSmoother(
    private val settleAlpha: Float = SETTLE_ALPHA,
    private val rackAlpha: Float = RACK_ALPHA,
    private val rackUpdates: Int = RACK_UPDATES,
    private val deadband: Float = DEADBAND
) {
    var focus: Float = Bokeh.INVALID
        private set

    private var rack = 0

    /** The smoothed focus after a new measurement [target] ([Bokeh.INVALID]: none); [newPoint] after a tap. */
    fun next(target: Float, newPoint: Boolean): Float {
        if (newPoint) rack = rackUpdates
        if (target <= Bokeh.INVALID) return focus
        if (focus <= Bokeh.INVALID) {
            focus = target
            rack = 0
            return focus
        }
        val delta = target - focus
        if (rack > 0) {
            rack--
            focus += delta * rackAlpha
        } else if (abs(delta) >= deadband) {
            focus += delta * settleAlpha
        }
        return focus
    }

    fun reset() {
        focus = Bokeh.INVALID
        rack = 0
    }

    companion object {
        const val SETTLE_ALPHA = 0.25f
        const val RACK_ALPHA = 0.6f
        const val RACK_UPDATES = 4
        const val DEADBAND = 0.03f
    }
}
