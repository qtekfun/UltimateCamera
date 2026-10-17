package com.qtekfun.ultimatecamera.core.recording

import android.hardware.camera2.CameraManager
import android.os.Handler
import android.util.Log
import com.qtekfun.ultimatecamera.core.camera.DepthFrame
import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.camera.DepthSupport
import com.qtekfun.ultimatecamera.core.camera.ImagePoint
import com.qtekfun.ultimatecamera.core.camera.PixelSize
import com.qtekfun.ultimatecamera.core.camera.PortraitProbe
import com.qtekfun.ultimatecamera.core.camera.SessionRejectedException
import com.qtekfun.ultimatecamera.core.camera.VideoDepthOutputs
import com.qtekfun.ultimatecamera.core.camera.VideoDepthProbe
import com.qtekfun.ultimatecamera.core.camera.VideoDepthRates
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.LivePortraitPlan
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurCause
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurLevel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Portrait in video (D89, REQ-PHO-005) for [CameraController]: before a session opens, [prepare] decides
 * ([VideoPortraitRules]) and makes the depth outputs to add to it; once it opened, [attach] starts the depth worker
 * (smoothed depth and focus, [VideoDepthSmoother]) and the renderer's blur, both into the recording
 * ([CameraRenderer.videoPortrait]) and into the viewfinder (D87's live preview). A refused session ([refused]) or
 * silent depth outputs drop the source for that camera for the run; the governor's step downs reach [notices].
 */
class VideoPortraitWire internal constructor(private val manager: CameraManager, private val handler: Handler) {
    /** The setting, Experimental and the chip; read by the next [prepare]. */
    @Volatile var options = VideoPortraitOptions()

    /** The aperture of the blur (0 = no blur); applies from the next depth map, no reopen. */
    @Volatile var fNumber: Float = DEFAULT_F_NUMBER

    @Volatile private var focus: ImagePoint? = null
    private val newPoint = AtomicBoolean(false)

    private val _status = MutableStateFlow(VideoPortraitStatus())
    val status: StateFlow<VideoPortraitStatus> = _status.asStateFlow()

    private val _notices = MutableSharedFlow<VideoPortraitNotice>(extraBufferCapacity = NOTICE_BUFFER)
    val notices: SharedFlow<VideoPortraitNotice> = _notices.asSharedFlow()

    private val supportCache = HashMap<String, DepthSupport>()
    private val availability = PortraitAvailability()

    // Written on the controller's coroutine, read by the camera thread's watchdog.
    @Volatile private var cameraId: String? = null

    @Volatile private var running: VideoPortraitVerdict.Run? = null

    @Volatile private var outputs: VideoDepthOutputs? = null
    private var worker: LivePortraitPreview? = null
    private val smoother = VideoDepthSmoother()
    private val watchdog = Runnable { checkDepthArrived() }

    /** The blur's focus follows the tap (the AF point, D89), null = the centre; any thread. */
    fun setFocus(point: ImagePoint?) {
        focus = point
        newPoint.set(true)
    }

    private fun look(): PortraitLook = PortraitLook(fNumber, focusX = focus?.x ?: CENTRE, focusY = focus?.y ?: CENTRE)

    /**
     * Before [profile] opens on [cameraId]: the depth outputs to add to the session, or null (portrait off, blocked or
     * not offered; [status] says which). Probes depth only with the setting on, bounded and cached per camera.
     */
    suspend fun prepare(cameraId: String, profile: RecordingProfile, modest: Boolean): VideoDepthOutputs? {
        release()
        this.cameraId = cameraId
        val opts = options
        val support = if (opts.enabled) depthSupport(cameraId) else DepthSupport()
        val offered = VideoPortraitRules.sources(support, opts.experimental)
        val usable = availability.usable(cameraId, offered)
        val rates = if (opts.on && usable.isNotEmpty() && VideoPortraitRules.block(profile, modest) == null) {
            runCatching { VideoDepthProbe.read(manager, cameraId, support, PixelSize(profile.width, profile.height)) }.getOrDefault(VideoDepthRates())
        } else {
            VideoDepthRates()
        }
        val verdict = VideoPortraitRules.verdict(opts, profile, offered, usable, support, rates, modest)
        _status.value = VideoPortraitRules.status(verdict, opts.on)
        if (verdict !is VideoPortraitVerdict.Run) return null
        Log.i(TAG, "video portrait: ${verdict.source} ${verdict.outputs}, depth every ${verdict.depthEvery}, start ${verdict.start}")
        running = verdict
        val stereo = support.stereo.takeIf { verdict.source == DepthSource.STEREO }
        return VideoDepthOutputs(verdict.outputs, stereo, verdict.depthEvery, handler).also { outputs = it }
    }

    /**
     * Opens the session: with [depth] (from [prepare]) through [withDepth], else, or when the camera refuses the depth
     * outputs ([SessionRejectedException], then [refused]), the plain session through [plain]: a camera that refuses
     * depth with video still records video.
     */
    suspend fun <T> open(depth: VideoDepthOutputs?, withDepth: suspend (VideoDepthOutputs) -> T, plain: suspend () -> T): T {
        if (depth == null) return plain()
        return try {
            withDepth(depth)
        } catch (_: SessionRejectedException) {
            refused()
            plain()
        }
    }

    /** The camera refused the session with [prepare]'s outputs: that source is not offered again on this camera this run. */
    fun refused() {
        val run = running ?: return
        cameraId?.let { availability.markFailed(it, run.source) }
        Log.w(TAG, "video portrait: session with ${run.source} refused, plain video")
        release()
        _status.update { it.copy(source = null, level = VideoBlurLevel.OFF, block = VideoPortraitBlock.SESSION_REFUSED) }
    }

    /** The session opened with [prepare]'s outputs: depth worker, viewfinder blur and the recording's blur on [renderer]. */
    fun attach(renderer: CameraRenderer, profile: RecordingProfile, modest: Boolean) {
        val run = running ?: return
        val out = outputs ?: return
        smoother.reset()
        setFocus(null)
        // The viewfinder shows the blur too (D87's live stage, paced on its own); without it the take is still blurred.
        renderer.setLivePortrait(LivePortraitPlan.of(profile.width, profile.height, modest), profile.fps)
        renderer.livePortraitProblem?.let { problem ->
            Log.w(TAG, "video portrait: plain viewfinder ($problem)")
            renderer.setLivePortrait(null)
        }
        val pass = renderer.videoPortrait
        pass.onLevel = { level, cause -> onLevel(level, cause) }
        pass.configure(run.start, profile.fps)
        val live = LivePortraitPreview(VideoPortraitRules.minDepthIntervalNanos(profile.fps, run.depthEvery), ::look, { d -> deliver(renderer, d) })
        worker = live
        out.consumer = { frame: DepthFrame -> live.onDepth(frame) }
        handler.postDelayed(watchdog, NO_DEPTH_TIMEOUT_MS)
    }

    /** Depth worker thread: the raw live depth smoothed, to the recording and the viewfinder. */
    private fun deliver(renderer: CameraRenderer, raw: LiveDepth) {
        val depth = smoother.next(raw.inverse, look(), newPoint.getAndSet(false))
        renderer.videoPortrait.setDepth(depth)
        renderer.setLiveDepth(depth)
    }

    /** GL thread: the governor moved the blur to [level]. */
    private fun onLevel(level: VideoBlurLevel, cause: VideoBlurCause) {
        Log.w(TAG, "video portrait: $cause, now $level")
        _status.update { VideoPortraitRules.afterChange(it, level, cause) }
        _notices.tryEmit(VideoPortraitRules.notice(level, cause))
    }

    /** Camera thread: outputs that stay silent after the session opened mean the HAL accepted them on paper only. */
    private fun checkDepthArrived() {
        val out = outputs ?: return
        if (out.framesDelivered > 0) return
        val run = running ?: return
        cameraId?.let { availability.markFailed(it, run.source) }
        Log.w(TAG, "video portrait: no depth from ${run.source} in $NO_DEPTH_TIMEOUT_MS ms")
        out.consumer = null
        _status.update { it.copy(source = null, level = VideoBlurLevel.OFF, block = VideoPortraitBlock.NO_DEPTH_FRAMES) }
        _notices.tryEmit(VideoPortraitNotice.TurnedOff(VideoPortraitBlock.NO_DEPTH_FRAMES))
    }

    private suspend fun depthSupport(cameraId: String): DepthSupport = supportCache[cameraId] ?: run {
        // Detached and bounded, like the photo mode's probe: a blocked vendor service must not hold the camera.
        val probe = CoroutineScope(Dispatchers.IO).async { PortraitProbe.read(manager, cameraId) }
        (withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe.await() } ?: DepthSupport().also { probe.cancel() }).also { supportCache[cameraId] = it }
    }

    /** Stops the depth worker and closes the depth outputs (the stream closes them too). */
    fun release() {
        handler.removeCallbacks(watchdog)
        worker?.close()
        worker = null
        outputs?.close()
        outputs = null
        running = null
    }

    private companion object {
        const val TAG = "UcPortrait"
        const val DEFAULT_F_NUMBER = 2.8f
        const val CENTRE = 0.5f
        const val NOTICE_BUFFER = 4
        const val NO_DEPTH_TIMEOUT_MS = 2_000L
        const val PROBE_TIMEOUT_MS = 3_000L
    }
}
