package com.qtekfun.ultimatecamera.core.gpu.gl

import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurCause
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurGovernor
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurLevel
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoPortraitPlan
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoPortraitShaders
import java.util.concurrent.atomic.AtomicReference

/**
 * Background blur baked into the recording (D89, REQ-PHO-005), on [CameraRenderer]'s GL thread. Per encoded frame,
 * when a depth map is there and the rung is not off: the camera frame is drawn twice into reduced copies (one without
 * the LUT, the guide; one with the recording LUT), the copy is blurred by the portrait gather shader with the CoC from
 * the latest [LiveDepth], the sharp full-size frame goes into the encoder as always, and [VideoPortraitShaders.COMPOSITE]
 * blends the blurred copy over it with an edge-aware alpha. A [VideoBlurGovernor] judges every frame's GL time and the
 * encoder's dropped frames and steps the blur down (or off) rather than let the take lose frames; [onLevel] reports it.
 * Any GL failure turns the blur off for the session and the take goes on plain. The proxy and the monitor stream are
 * not blurred (their own passes from the camera texture). Setup and rung changes allocate; frames do not.
 */
class VideoPortraitPass internal constructor(
    private val videoWidth: Int,
    private val videoHeight: Int,
    /** Makes the GL objects of one rung ([VideoPortraitStage] on the renderer's context). */
    private val newStage: (VideoPortraitPlan) -> VideoPortraitStageOps
) {
    private class Config(val start: VideoBlurLevel, val fps: Int)

    private val pendingConfig = AtomicReference<Config?>(null)
    private val pendingDepth = AtomicReference<LiveDepth?>(null)
    private var governor: VideoBlurGovernor? = null
    private var stage: VideoPortraitStageOps? = null
    private var depth: LiveDepth? = null
    private var drew = false

    /** The rung in use ([VideoBlurLevel.OFF] when off or not configured). Any thread. */
    @Volatile var level: VideoBlurLevel = VideoBlurLevel.OFF
        private set

    /** Why the GPU pass failed, or null. */
    @Volatile var problem: String? = null
        private set

    /** Called on the GL thread when the governor changes the rung during a session. */
    @Volatile var onLevel: ((VideoBlurLevel, VideoBlurCause) -> Unit)? = null

    /** Turns the blur on at [start] for a camera at [fps], or off with null. Any thread; applied before the next encoded frame. */
    fun configure(start: VideoBlurLevel?, fps: Int) {
        pendingConfig.set(Config(start ?: VideoBlurLevel.OFF, fps.coerceAtLeast(1)))
        level = start ?: VideoBlurLevel.OFF
    }

    /** The latest smoothed depth; any thread, never blocks (a newer one replaces it). */
    fun setDepth(update: LiveDepth) {
        pendingDepth.set(update)
    }

    /** A take starts: the governor's window and warm-up start again (GL thread). */
    internal fun takeStarted() {
        governor?.restart()
    }

    /**
     * Before the sharp frame is drawn into the encoder surface (the camera frame [cameraTexture] seen through
     * [texMatrix]): the reduced passes. True when [afterEncoder] must blend the blur in; the default framebuffer is
     * bound again either way.
     */
    @Suppress("TooGenericExceptionCaught") // GL drivers throw anything; the take must go on plain.
    internal fun beforeEncoder(cameraTexture: Int, texMatrix: FloatArray): Boolean {
        drew = false
        pendingConfig.getAndSet(null)?.let(::applyConfig)
        val update = pendingDepth.getAndSet(null)
        if (update != null) depth = update
        val s = stage ?: return false
        val d = depth ?: return false
        return try {
            if (update != null) s.uploadDepth(update)
            s.prepare(cameraTexture, texMatrix, d)
            drew = true
            true
        } catch (e: RuntimeException) {
            failed(e)
            false
        }
    }

    /** Blends the blurred copy over the sharp frame just drawn into the [width] x [height] encoder surface. */
    @Suppress("TooGenericExceptionCaught")
    internal fun afterEncoder(cameraTexture: Int, texMatrix: FloatArray, width: Int, height: Int) {
        val s = stage ?: return
        val d = depth ?: return
        try {
            s.composite(cameraTexture, texMatrix, width, height, d)
        } catch (e: RuntimeException) {
            drew = false
            failed(e)
        }
    }

    /** After the frame: its GL time and the encoder's newly dropped frames, for the governor (only frames that blurred). */
    internal fun frameDone(renderNanos: Long, droppedFrames: Int) {
        if (!drew) return
        drew = false
        val g = governor ?: return
        if (g.record(renderNanos, droppedFrames)) changeTo(g.level, g.cause ?: VideoBlurCause.SLOW_FRAMES)
    }

    private fun applyConfig(config: Config) {
        releaseStage()
        governor = if (config.start != VideoBlurLevel.OFF) VideoBlurGovernor(config.start, NANOS_PER_SECOND / config.fps) else null
        problem = null
        buildStage(config.start)
    }

    /** The stage of [target] (none for off); false when it could not be made (the blur is then off, reported). */
    @Suppress("TooGenericExceptionCaught")
    private fun buildStage(target: VideoBlurLevel): Boolean {
        level = target
        val plan = VideoPortraitPlan.of(target, videoWidth, videoHeight) ?: return true
        return try {
            val made = newStage(plan)
            stage = made
            depth?.let(made::uploadDepth)
            true
        } catch (e: RuntimeException) {
            failed(e)
            false
        }
    }

    private fun changeTo(next: VideoBlurLevel, cause: VideoBlurCause) {
        releaseStage()
        if (buildStage(next)) onLevel?.invoke(level, cause)
    }

    private fun failed(e: RuntimeException) {
        problem = "${e.javaClass.simpleName}: ${e.message}"
        releaseStage()
        level = VideoBlurLevel.OFF
        if (governor?.fail() == true) onLevel?.invoke(VideoBlurLevel.OFF, VideoBlurCause.GPU_FAILURE)
    }

    private fun releaseStage() {
        runCatching { stage?.release() }
        stage = null
    }

    /** GL thread, context current. */
    internal fun release() {
        releaseStage()
        governor = null
        depth = null
        level = VideoBlurLevel.OFF
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/** The GL work of one rung of [VideoPortraitPass] (an interface so the pass's decisions are tested on the JVM). */
internal interface VideoPortraitStageOps {
    /** Uploads the depth map, on a change only. */
    fun uploadDepth(update: LiveDepth)

    /** The reduced passes; leaves the default framebuffer bound, also on failure. */
    fun prepare(cameraTexture: Int, texMatrix: FloatArray, d: LiveDepth)

    /** The blend over the sharp frame in the bound default framebuffer of [width] x [height]. */
    fun composite(cameraTexture: Int, texMatrix: FloatArray, width: Int, height: Int, d: LiveDepth)

    fun release()
}
