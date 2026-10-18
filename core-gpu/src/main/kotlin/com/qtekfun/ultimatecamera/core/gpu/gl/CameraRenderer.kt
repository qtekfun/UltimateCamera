package com.qtekfun.ultimatecamera.core.gpu.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.LogSource
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.ProxyFrameGate
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorSettings
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.LivePortraitPacer
import com.qtekfun.ultimatecamera.core.gpu.portrait.LivePortraitPlan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The camera's GPU stage for recording (docs/01 pipeline): camera frames arrive in a SurfaceTexture; every frame is
 * drawn to the preview, and, while [startEncoding] is active, to the encoder surface with the camera timestamp. The two
 * passes have their own LUT ([setColor], REQ-COL-004): the recording LUT is baked into the file, the monitor LUT only
 * changes what the screen shows; with UC-Log (10-bit HLG only, REQ-COL-002) both passes start from the Log signal.
 * The camera session never changes when recording starts or stops: only the encoder surface is attached or detached
 * here, so REC is instant.
 *
 * Owns its GL thread. Public methods may be called from any thread; per frame there are no allocations.
 */
class CameraRenderer(
    depth: EglColorDepth,
    private val width: Int,
    private val height: Int,
    private val hlg: Boolean,
    /**
     * `SENSOR_ORIENTATION` of the camera. The camera service puts this turn into the SurfaceTexture transform (frames come
     * out upright for the phone's natural orientation); the encoder undoes it so files keep the sensor's landscape frame
     * plus an MP4 rotation hint, and the preview starts from it.
     */
    private val sensorOrientation: Int = 0,
    private val interpolation: LutInterpolation = LutInterpolation.HARDWARE
) : SurfaceTexture.OnFrameAvailableListener {
    private val thread = HandlerThread("uc-render", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private lateinit var dummy: EGLSurface
    private lateinit var recordRenderer: LutRenderer
    private lateinit var monitorRenderer: LutRenderer
    private var ucLog = false
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val matrix = FloatArray(16)
    private val previewMatrix = FloatArray(16)
    private val encoderMatrix = FloatArray(16)
    private val encoderTurn = turnFor((FULL_TURN - sensorOrientation) % FULL_TURN)

    /**
     * Clockwise turn in degrees (0, 90, 180, 270) applied to the naturally upright frame so it is upright on the rotated
     * display (the display's rotation); the recording is not affected.
     */
    @Volatile var previewRotation = 0
    private var fittedRotation = -1

    /** Mirror the preview left-right (front camera, REQ-CAM-001); the recording and the streams stay as the sensor sees. */
    @Volatile var mirrorPreview = false
    private val mirroredMatrix = FloatArray(16)

    private var previewEgl: EGLSurface? = null
    private var previewSize = 0 to 0
    private var previewViewport = IntArray(4)

    /** Monitoring tools of the preview (never the encoder); settings handed over to the GL thread, see [setMonitorSettings]. */
    private var monitor: PreviewMonitor? = null
    private val pendingMonitor = AtomicReference<MonitorSettings?>(null)

    private var encoderEgl: EGLSurface? = null
    private var encodeFromNanos = Long.MAX_VALUE

    /** Reduced copy for a remote monitor ([setStreamOutput]); GL thread only. */
    private var streamEgl: EGLSurface? = null
    private var streamOutput: StreamOutput? = null
    private var streamFrame = 0

    /**
     * Draws the preview only on every n-th camera frame (1 = every frame). Lowered when the phone runs hot (REQ-NFR-001,
     * D68): the encoder still gets every frame, only the screen refresh drops. Any thread.
     */
    @Volatile var previewEveryNthFrame = 1

    /** Live portrait preview (D87); GL thread only. Null: the plain preview. */
    private var portraitStage: LivePortraitStage? = null
    private var portraitPacer: LivePortraitPacer? = null
    private var portraitBlurred = false
    private val pendingPortraitDepth = AtomicReference<LiveDepth?>(null)
    private var sensorTexMatrix = matrix
    private val portraitMatrix = FloatArray(16)
    private val portraitScratch = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Why the live portrait preview was dropped (setup or a frame failed), or null; the preview is then plain. */
    @Volatile var livePortraitProblem: String? = null
        private set

    /** Current blur stride of the live portrait preview (1 = every preview frame), 0 when off; for the log and tests. */
    @Volatile var livePortraitStride = 0
        private set

    /**
     * Background blur baked into the recording (D89): configured from any thread, drawn on the GL thread around the
     * encoder pass; off unless configured.
     */
    val videoPortrait = VideoPortraitPass(width, height) { plan -> VideoPortraitStage(plan) { recordRenderer } }
    private var seenGapDrops = 0
    private var newGapDrops = 0

    /** The renderer a shared-context proxy draws with; one instance, so the per-frame call allocates nothing (D14). */
    private val proxyRenderer: (Boolean) -> LutRenderer = { monitorLook -> if (monitorLook) monitorRenderer else recordRenderer }

    /** `GL_RENDERER` of the context (e.g. "Mali-G715"), read once at creation: one input of the phone's tier (D68). */
    @Volatile var glRenderer: String = ""
        private set

    /** The surface the camera session renders into, sized [width] x [height]. */
    lateinit var cameraSurface: Surface
        private set

    /** Frames drawn to the preview, and to the encoder since the last [startEncoding]. */
    val framesRendered = AtomicInteger()
    val framesEncoded = AtomicInteger()

    /** Camera timestamp of the first frame encoded since the last [startEncoding] (-1 until there is one): the take's start. */
    @Volatile var firstEncodedNanos = -1L
        private set

    /** Camera timestamp of the first frame drawn into the current encoder surface (-1 until there is one): a part's start (D47, D69). */
    @Volatile var partFirstEncodedNanos = -1L
        private set

    /** Last frame's render time on the GL thread, in nanoseconds (for the status bar and the probe). */
    @Volatile var lastRenderNanos = 0L
        private set

    /** Recent render times, for the status bar's p95 (REQ-NFR-001: the GPU stage must fit the frame budget). */
    val renderTimes = RenderTimeWindow()

    /** First GL/EGL failure; the preview and recording stop drawing when set. */
    @Volatile var error: String? = null
        private set

    @Volatile private var released = false

    init {
        runOnGl {
            egl = EglCore(depth, recordable = true)
            // A 1x1 pbuffer keeps a current context when there is neither preview nor encoder.
            dummy = egl.createPbufferSurface(1, 1)
            egl.makeCurrent(dummy)
            recordRenderer = LutRenderer(InputKind.EXTERNAL_OES, interpolation)
            monitorRenderer = LutRenderer(InputKind.EXTERNAL_OES, interpolation)
            proxy = ProxyStage(egl, dummy, interpolation)
            glRenderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
            texture = LutRenderer.createExternalTexture()
            surfaceTexture = SurfaceTexture(texture).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener(this@CameraRenderer, handler)
            }
            cameraSurface = Surface(surfaceTexture)
        }
    }

    /** True when the EGL config can tag BT.2020 HLG (the encoder surface then carries the right colour space). */
    val hlgTagged: Boolean get() = hlg && egl.supportsBt2020Hlg

    /** The preview surface can be tagged BT.2020 HLG (10-bit HLG stream and EGL_EXT_gl_colorspace_bt2020_hlg). */
    val canTagPreviewHlg: Boolean get() = hlgTagged

    /**
     * Tags the preview window surface BT.2020 HLG (HDR viewfinder, D63) when the preview shows the HLG signal as it comes,
     * so an HDR display shows it as HDR. Changing it re-creates the preview surface (one frame, never the encoder's).
     * The monitoring overlays and scopes are drawn into the same surface and keep working (their white is HLG white).
     */
    @Volatile var previewHlg: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            runOnGl { previewSurface?.let(::attachPreview) }
        }

    private var previewSurface: Surface? = null

    /** Shows frames on [surface] (letterboxed to the video aspect), or stops showing them with null. */
    fun setPreview(surface: Surface?) = runOnGl { attachPreview(surface) }

    private fun attachPreview(surface: Surface?) {
        previewEgl?.let {
            egl.makeCurrent(dummy)
            egl.releaseSurface(it)
        }
        previewEgl = null
        previewSurface = surface
        if (surface != null && surface.isValid) {
            runCatching { egl.createWindowSurface(surface, hlg = previewHlg && hlgTagged) }.onSuccess { p ->
                previewEgl = p
                previewSize = egl.size(p)
                fittedRotation = -1
                egl.makeCurrent(p)
                // A full display queue must never block the render thread (and with it the encoder).
                egl.setSwapInterval(0)
            }.onFailure { error = error ?: "preview: ${it.message}" }
        }
    }

    /**
     * Also draws the monitored image (monitor look, without overlays, in the recording's orientation) into
     * [output] at its reduced size and rate, for a remote monitor (REQ-NET-004, D56); null stops it. Never touches the
     * encoder path: drawn after the encoder and the preview, and dropped when its surface goes away.
     */
    fun setStreamOutput(output: StreamOutput?) = runOnGl {
        streamEgl?.let {
            egl.makeCurrent(dummy)
            egl.releaseSurface(it)
        }
        streamEgl = null
        streamOutput = null
        if (output != null && output.surface.isValid) {
            runCatching { egl.createWindowSurface(output.surface) }.onSuccess { s ->
                streamEgl = s
                streamOutput = output
                streamFrame = 0
                egl.makeCurrent(s)
                // The consumer (the WebRTC encoder) holds one frame at a time: never block the render thread on it.
                egl.setSwapInterval(0)
            }.onFailure { error = error ?: "stream: ${it.message}" }
        }
    }

    /**
     * Shows the preview blurred by depth (portrait, D87) on a copy of [plan]'s size, paced for a camera at [fps]; null
     * goes back to the plain preview. Until the first [setLiveDepth] the preview stays plain. A failure here or on a
     * later frame leaves the plain preview ([livePortraitProblem]); it never stops the camera.
     */
    @Suppress("TooGenericExceptionCaught") // GL drivers throw anything; the plain preview is the fallback.
    fun setLivePortrait(plan: LivePortraitPlan?, fps: Int = DEFAULT_FPS) = runOnGl {
        egl.makeCurrent(dummy)
        dropLivePortrait()
        pendingPortraitDepth.set(null)
        if (plan == null) return@runOnGl
        try {
            portraitStage = LivePortraitStage(plan)
            portraitPacer = LivePortraitPacer.forPlan(plan, fps)
            livePortraitStride = plan.blurEvery
            livePortraitProblem = null
        } catch (e: RuntimeException) {
            dropLivePortrait()
            livePortraitProblem = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Hands the latest depth to the live portrait preview; any thread, never blocks (a newer one replaces it). */
    fun setLiveDepth(depth: LiveDepth) {
        pendingPortraitDepth.set(depth)
    }

    private fun dropLivePortrait() {
        runCatching { portraitStage?.release() }
        portraitStage = null
        portraitPacer = null
        livePortraitStride = 0
    }

    /** Applies [lut] to preview and recording from the next frame (null = no LUT). */
    fun setLut(lut: CubeLut?) = setColor(ucLog = false, recordLut = lut, monitorLut = lut)

    /**
     * From the next frame: the encoder gets the camera signal (converted to UC-Log when [ucLog]) through [recordLut], and
     * the preview the same signal through [monitorLut]. To show the recording plus a viewing LUT, pass both chained
     * ([com.qtekfun.ultimatecamera.core.gpu.LutCompose]). [ucLog] is ignored unless the stream is 10-bit HLG. Costs
     * the LUT uploads, plus two shader compiles when [ucLog] changes; call on a change, never per frame.
     */
    fun setColor(ucLog: Boolean, recordLut: CubeLut?, monitorLut: CubeLut?) = runOnGl {
        egl.makeCurrent(dummy)
        val log = ucLog && hlg
        if (log != this.ucLog) {
            recordRenderer.release()
            monitorRenderer.release()
            val source = if (log) LogSource.HLG else null
            recordRenderer = LutRenderer(InputKind.EXTERNAL_OES, interpolation, source)
            monitorRenderer = LutRenderer(InputKind.EXTERNAL_OES, interpolation, source)
            this.ucLog = log
        }
        recordRenderer.setLut(recordLut)
        monitorRenderer.setLut(monitorLut)
        proxy.setLook(log, recordLut, monitorLut)
    }

    /**
     * Overlays and scope drawn on the preview only (REQ-MON-001, D46); [MonitorSettings.OFF] draws the plain preview.
     * Any thread, never blocks: applied on the GL thread before the next preview frame.
     */
    fun setMonitorSettings(settings: MonitorSettings) {
        pendingMonitor.set(settings)
    }

    /**
     * Starts drawing every camera frame stamped at or after [fromNanos] into the encoder's input [surface]. [hlgSurface]
     * tags the EGL surface BT.2020 HLG; false when the recorded signal is no longer HLG (UC-Log or a baked LUT).
     */
    fun startEncoding(surface: Surface, fromNanos: Long, hlgSurface: Boolean = hlg) = runOnGl {
        framesEncoded.set(0)
        encoderGaps.reset()
        seenGapDrops = 0
        videoPortrait.takeStarted()
        firstEncodedNanos = -1L
        partFirstEncodedNanos = -1L
        encoderHlg = hlgSurface
        encoderEgl = egl.createWindowSurface(surface, hlgSurface)
        encodeFromNanos = fromNanos
    }

    /**
     * Moves encoding to another encoder's [surface] between two frames, keeping the take's start and counters: frames
     * before go to the old encoder, every later one to the new (a take split into parts, D47). The old encoder can be
     * ended once this returns.
     */
    fun switchEncoding(surface: Surface) = runOnGl {
        encoderEgl?.let {
            egl.makeCurrent(dummy)
            egl.releaseSurface(it)
        }
        encoderEgl = egl.createWindowSurface(surface, encoderHlg)
        partFirstEncodedNanos = -1L
    }

    private var encoderHlg = hlg

    /** Stops feeding the encoder; when this returns no more frames reach it, so it can be ended. */
    fun stopEncoding() = runOnGl {
        encodeFromNanos = Long.MAX_VALUE
        encoderEgl?.let {
            egl.makeCurrent(dummy)
            egl.releaseSurface(it)
        }
        encoderEgl = null
    }

    /** The proxy's 8-bit input (D59); created on the GL thread with the context. */
    private lateinit var proxy: ProxyStage

    /** Why the proxy stopped receiving frames during the take (its encoder stalled), or null. */
    val proxyProblem: String? get() = if (::proxy.isInitialized) proxy.problem else null

    /**
     * Also draws frames stamped at or after [fromNanos] into a proxy encoder's [surface] (D59), after the main encoder,
     * with the same timestamps, scaled to [width] x [height] and thinned by [gate] against the proxy encoder's output
     * count [encoded]. [monitorLook] draws the monitor view (Rec.709 on screen) instead of the recorded signal: a 10-bit
     * HLG or UC-Log take then still gives a proxy that looks right in any player. The proxy surface is always 8-bit
     * ([ProxyStage]).
     */
    fun startProxy(surface: Surface, width: Int, height: Int, fromNanos: Long, gate: ProxyFrameGate, encoded: AtomicInteger, monitorLook: Boolean) = runOnGl {
        proxy.start(surface, width, height, fromNanos, gate, encoded, monitorLook)
    }

    /** Stops feeding the proxy encoder; when this returns no more frames reach it. */
    fun stopProxy() = runOnGl { proxy.stop() }

    // Render-thread boundary: a GL/EGL failure is recorded instead of killing the app mid-take.
    @Suppress("TooGenericExceptionCaught")
    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released) return
        val start = System.nanoTime()
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(matrix)
            val timestamp = surfaceTexture.timestamp
            // One sensor-turn product per frame, shared by the encoder, the proxy and the stream.
            val encoderTex = encoderTexMatrix()
            sensorTexMatrix = encoderTex
            encoderEgl?.takeIf { timestamp >= encodeFromNanos }?.let { drawEncoder(it, timestamp, encoderTex) }
            proxy.draw(texture, encoderTex, timestamp, proxyRenderer)
            val frame = framesRendered.getAndIncrement()
            if (PreviewPacing.draws(frame, previewEveryNthFrame)) previewEgl?.let(::drawPreview)
            streamEgl?.let { drawStream(it, encoderTex) }
        } catch (e: RuntimeException) {
            error = error ?: "${e.javaClass.simpleName}: ${e.message}"
        }
        lastRenderNanos = System.nanoTime() - start
        renderTimes.record(lastRenderNanos)
        videoPortrait.frameDone(lastRenderNanos, newGapDrops)
        newGapDrops = 0
        portraitPacer?.let {
            it.record(portraitBlurred, lastRenderNanos)
            livePortraitStride = it.stride
        }
        portraitBlurred = false
    }

    /** Draws the frame in the sensor's own orientation (the camera's transform turn undone) into the encoder. */
    private fun drawEncoder(enc: EGLSurface, timestamp: Long, texMatrix: FloatArray) {
        egl.makeCurrent(enc)
        // Portrait in video (D89): reduced blur passes first, the sharp frame as always, then the blur blended over it.
        val blur = videoPortrait.beforeEncoder(texture, texMatrix)
        recordRenderer.draw(texture, width, height, texMatrix)
        if (blur) videoPortrait.afterEncoder(texture, texMatrix, width, height)
        egl.setPresentationTime(enc, timestamp)
        egl.swapBuffers(enc)
        if (framesEncoded.getAndIncrement() == 0) firstEncodedNanos = timestamp
        if (partFirstEncodedNanos < 0) partFirstEncodedNanos = timestamp
        countGap(timestamp)
        val drops = encoderGaps.drops.get()
        newGapDrops = drops - seenGapDrops
        seenGapDrops = drops
    }

    /**
     * Expected camera frame interval of the take (1 s / fps), set before [startEncoding]; 0 disables gap counting. A
     * step of more than 1.5 intervals between two encoded frames counts the missing frames in [encoderGapDrops], so a
     * camera running a fraction of a percent off its nominal rate is not mistaken for drops.
     */
    var expectedFrameIntervalNanos: Long
        get() = encoderGaps.expectedIntervalNanos
        set(value) {
            encoderGaps.expectedIntervalNanos = value
        }

    /** Gaps between consecutive encoded frames since [startEncoding]: missing frames, gap count, largest step. */
    val encoderGaps = FrameGapCounter()

    /** Frames missing between consecutive encoded frames since [startEncoding] (see [expectedFrameIntervalNanos]). */
    val encoderGapDrops: AtomicInteger get() = encoderGaps.drops

    private fun countGap(timestamp: Long) = encoderGaps.onFrame(timestamp)

    /** The frame's transform with the camera's turn undone (sensor orientation), for the encoders. */
    private fun encoderTexMatrix(): FloatArray = encoderTurn?.let {
        Matrix.multiplyMM(encoderMatrix, 0, matrix, 0, it, 0)
        encoderMatrix
    } ?: matrix

    private fun drawPreview(preview: EGLSurface) {
        egl.makeCurrent(preview)
        val rotation = previewRotation
        if (rotation != fittedRotation) {
            // Only on a change (new surface or the phone turned): allocation-free otherwise.
            // The transformed frame is portrait-shaped when the sensor is mounted at 90/270; a quarter turn swaps again.
            previewViewport = fit(previewSize, (sensorOrientation + rotation) % HALF_TURN != 0)
            fittedRotation = rotation
        }
        // The display rotation turns the screen counter-clockwise, so the image is turned back the opposite way (a
        // clockwise turn by `rotation` was wrong: both landscapes showed upside down, seen on the OPPO CPH2841).
        val turn = turnFor((FULL_TURN - rotation) % FULL_TURN)
        val m = if (turn != null) {
            Matrix.multiplyMM(previewMatrix, 0, matrix, 0, turn, 0)
            previewMatrix
        } else {
            matrix
        }.let { turned ->
            if (mirrorPreview) {
                // Applied first to the screen's texture coordinates: s → 1 − s flips the picture on screen.
                Matrix.multiplyMM(mirroredMatrix, 0, turned, 0, TEX_MIRROR_S, 0)
                mirroredMatrix
            } else {
                turned
            }
        }
        val monitored = monitorForFrame()
        // The blurred portrait preview (photo mode) replaces the plain one; the monitoring tools are for video.
        val portrait = portraitStage?.let { drawLivePortrait(it, rotation) } == true
        when {
            portrait -> Unit
            monitored != null && monitored.needsOffscreen -> drawMonitoredPreview(monitored, m)
            else -> {
                // Plain preview; with only a scope on, its panel is drawn over it and the screen-sized offscreen pass
                // runs only on the scope's analysis frames (D68).
                GLES30.glViewport(0, 0, previewSize.first, previewSize.second)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                monitorRenderer.draw(texture, previewViewport[2], previewViewport[3], m, previewViewport[0], previewViewport[1])
                monitored?.drawScopeOnly(previewViewport[2], previewViewport[3], previewSize.first, previewSize.second)
            }
        }
        if (!egl.swapBuffers(preview)) {
            // The preview went away (screen off, view detached): keep recording without it.
            egl.makeCurrent(dummy)
            egl.releaseSurface(preview)
            previewEgl = null
        }
    }

    /**
     * The live portrait preview (D87): the latest depth uploaded, the blur run when the pacer says so (else the last
     * blurred picture is shown again), presented into the letterboxed viewport. False when there is nothing blurred to
     * show yet or the stage failed (it is then dropped): the caller draws the plain preview.
     */
    @Suppress("TooGenericExceptionCaught") // A failing portrait pass must leave the plain viewfinder, never a black one.
    private fun drawLivePortrait(stage: LivePortraitStage, rotation: Int): Boolean = try {
        pendingPortraitDepth.getAndSet(null)?.let(stage::setDepth)
        if (stage.hasDepth) {
            if (portraitPacer?.next() != false || !stage.hasPicture) {
                stage.blur { w, h -> monitorRenderer.draw(texture, w, h, sensorTexMatrix) }
                portraitBlurred = true
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, previewSize.first, previewSize.second)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            stage.present(previewViewport[0], previewViewport[1], previewViewport[2], previewViewport[3], livePortraitMatrix(rotation))
        }
        stage.hasDepth
    } catch (e: RuntimeException) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        livePortraitProblem = "${e.javaClass.simpleName}: ${e.message}"
        dropLivePortrait()
        false
    }

    /**
     * Screen → sensor-frame texture coordinates of the blurred copy. The camera frame reaches the screen through
     * `matrix · turn · mirror` and the sensor-frame copy through `matrix · encoderTurn`, so the copy is sampled at
     * `encoderTurn⁻¹ · turn · mirror` (encoderTurn⁻¹ is the sensor turn itself). Allocation-free.
     */
    private fun livePortraitMatrix(rotation: Int): FloatArray {
        val sensor = turnFor(sensorOrientation % FULL_TURN) ?: identity
        val turn = turnFor((FULL_TURN - rotation) % FULL_TURN) ?: identity
        Matrix.multiplyMM(portraitScratch, 0, sensor, 0, turn, 0)
        if (!mirrorPreview) return portraitScratch
        Matrix.multiplyMM(portraitMatrix, 0, portraitScratch, 0, TEX_MIRROR_S, 0)
        return portraitMatrix
    }

    /** Every [StreamOutput.everyNthFrame]-th frame, the monitor look at the stream's size; allocation-free. */
    private fun drawStream(surface: EGLSurface, texMatrix: FloatArray) {
        val output = streamOutput ?: return
        val frame = streamFrame++
        if (frame % output.everyNthFrame != 0) return
        egl.makeCurrent(surface)
        monitorRenderer.draw(texture, output.width, output.height, texMatrix)
        if (!egl.swapBuffers(surface)) {
            // The monitor link closed its surface: stop streaming, keep recording.
            egl.makeCurrent(dummy)
            egl.releaseSurface(surface)
            streamEgl = null
            streamOutput = null
        }
    }

    /** Texture-coordinate matrix that turns the image [degrees] clockwise on screen, or null for none. */
    private fun turnFor(degrees: Int): FloatArray? = when (degrees) {
        // GL texture t grows upwards, so a clockwise turn on screen is the counter-clockwise turn of (s, t).
        QUARTER_TURN -> TEX_ROTATE_CCW
        HALF_TURN -> ROTATE_180
        THREE_QUARTER_TURN -> TEX_ROTATE_CW
        else -> null
    }

    /** Applies pending monitor settings (GL thread, context current); the monitor to draw with, or null when off. */
    private fun monitorForFrame(): PreviewMonitor? {
        pendingMonitor.getAndSet(null)?.let { settings ->
            if (settings.active || monitor != null) (monitor ?: PreviewMonitor().also { monitor = it }).apply(settings)
        }
        return monitor?.takeIf { it.active }
    }

    /**
     * The preview with monitoring tools: the monitored image (same pass as the plain preview) goes into the monitor's
     * offscreen target at the image area's size, then the monitor draws it with overlays and scope into the preview.
     */
    private fun drawMonitoredPreview(monitored: PreviewMonitor, texMatrix: FloatArray) {
        monitored.beginFrame(previewViewport[2], previewViewport[3])
        monitorRenderer.draw(texture, previewViewport[2], previewViewport[3], texMatrix)
        monitored.finishFrame(previewSize.first, previewSize.second)
    }

    /** Largest viewport with the video's aspect ratio inside the preview surface, centred: x, y, width, height. */
    private fun fit(size: Pair<Int, Int>, swapped: Boolean): IntArray {
        val (sw, sh) = size
        val width = if (swapped) this.height else this.width
        val height = if (swapped) this.width else this.height
        val scale = minOf(sw.toFloat() / width, sh.toFloat() / height)
        val w = (width * scale).toInt()
        val h = (height * scale).toInt()
        return intArrayOf((sw - w) / 2, (sh - h) / 2, w, h)
    }

    /** Frees GL resources and the thread. The camera session must be closed first. */
    fun release() {
        if (released) return
        runOnGl {
            released = true
            surfaceTexture.setOnFrameAvailableListener(null)
            cameraSurface.release()
            surfaceTexture.release()
            egl.makeCurrent(dummy)
            recordRenderer.release()
            monitorRenderer.release()
            monitor?.release()
            dropLivePortrait()
            videoPortrait.release()
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            previewEgl?.let(egl::releaseSurface)
            encoderEgl?.let(egl::releaseSurface)
            streamEgl?.let(egl::releaseSurface)
            proxy.release()
            egl.makeCurrent(dummy)
            egl.releaseSurface(dummy)
            egl.release()
        }
        thread.quitSafely()
    }

    /** Runs [block] on the GL thread and waits for it; exceptions are rethrown to the caller. */
    @Suppress("TooGenericExceptionCaught") // Not handled here: carried to the calling thread and rethrown there.
    private fun runOnGl(block: () -> Unit) {
        if (Thread.currentThread() == thread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        check(done.await(GL_TIMEOUT_S, TimeUnit.SECONDS)) { "GL thread did not answer in $GL_TIMEOUT_S s" }
        failure?.let { throw it }
    }

    private companion object {
        const val GL_TIMEOUT_S = 5L
        const val DEFAULT_FPS = 30

        const val FULL_TURN = 360
        const val QUARTER_TURN = 90
        const val HALF_TURN = 180
        const val THREE_QUARTER_TURN = 270

        /** Texture coordinates (s, t) → (t, 1 − s), column-major. */
        val TEX_ROTATE_CW = floatArrayOf(0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

        /** Texture coordinates (s, t) → (1 − s, t), column-major: a left-right mirror. */
        val TEX_MIRROR_S = floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)

        /** Texture coordinates (s, t) → (1 − t, s), column-major. */
        val TEX_ROTATE_CCW = floatArrayOf(0f, 1f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f)

        /** Texture coordinates (s, t) → (1 − s, 1 − t), column-major. */
        val ROTATE_180 = floatArrayOf(-1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    }
}
