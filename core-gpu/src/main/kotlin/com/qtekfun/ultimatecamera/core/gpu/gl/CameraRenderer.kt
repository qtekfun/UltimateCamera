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
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The camera's GPU stage for recording (docs/01 pipeline): camera frames arrive in a SurfaceTexture; every frame is
 * drawn (through the LUT when one is set) to the preview, and, while [startEncoding] is active, to the encoder surface
 * with the camera timestamp. The camera session never changes when recording starts or stops: only the encoder surface
 * is attached or detached here, so REC is instant.
 *
 * Owns its GL thread. Public methods may be called from any thread; per frame there are no allocations.
 */
class CameraRenderer(
    depth: EglColorDepth,
    private val width: Int,
    private val height: Int,
    private val hlg: Boolean,
    interpolation: LutInterpolation = LutInterpolation.HARDWARE
) : SurfaceTexture.OnFrameAvailableListener {
    private val thread = HandlerThread("uc-render", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var egl: EglCore
    private lateinit var dummy: EGLSurface
    private lateinit var renderer: LutRenderer
    private var texture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val matrix = FloatArray(16)
    private val previewMatrix = FloatArray(16)

    /**
     * Turns the preview (not the recording, which carries an orientation hint) upside down, for the landscape
     * orientation opposite to the sensor's.
     */
    @Volatile var previewRotated180 = false

    private var previewEgl: EGLSurface? = null
    private var previewSize = 0 to 0
    private var previewViewport = IntArray(4)

    private var encoderEgl: EGLSurface? = null
    private var encodeFromNanos = Long.MAX_VALUE

    /** The surface the camera session renders into, sized [width] x [height]. */
    lateinit var cameraSurface: Surface
        private set

    /** Frames drawn to the preview, and to the encoder since the last [startEncoding]. */
    val framesRendered = AtomicInteger()
    val framesEncoded = AtomicInteger()

    /** Last frame's render time on the GL thread, in nanoseconds (for the status bar and the probe). */
    @Volatile var lastRenderNanos = 0L
        private set

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
            renderer = LutRenderer(InputKind.EXTERNAL_OES, interpolation)
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

    /** Shows frames on [surface] (letterboxed to the video aspect), or stops showing them with null. */
    fun setPreview(surface: Surface?) = runOnGl {
        previewEgl?.let {
            egl.makeCurrent(dummy)
            egl.releaseSurface(it)
        }
        previewEgl = null
        if (surface != null && surface.isValid) {
            runCatching { egl.createWindowSurface(surface) }.onSuccess { p ->
                previewEgl = p
                previewSize = egl.size(p)
                previewViewport = fit(previewSize)
                egl.makeCurrent(p)
                // A full display queue must never block the render thread (and with it the encoder).
                egl.setSwapInterval(0)
            }.onFailure { error = error ?: "preview: ${it.message}" }
        }
    }

    /** Applies [lut] to preview and recording from the next frame (null = no LUT). */
    fun setLut(lut: CubeLut?) = runOnGl {
        egl.makeCurrent(dummy)
        renderer.setLut(lut)
    }

    /** Starts drawing every camera frame stamped at or after [fromNanos] into the encoder's input [surface]. */
    fun startEncoding(surface: Surface, fromNanos: Long) = runOnGl {
        framesEncoded.set(0)
        encoderEgl = egl.createWindowSurface(surface, hlg)
        encodeFromNanos = fromNanos
    }

    /** Stops feeding the encoder; when this returns no more frames reach it, so it can be ended. */
    fun stopEncoding() = runOnGl {
        encodeFromNanos = Long.MAX_VALUE
        encoderEgl?.let {
            egl.makeCurrent(dummy)
            egl.releaseSurface(it)
        }
        encoderEgl = null
    }

    // Render-thread boundary: a GL/EGL failure is recorded instead of killing the app mid-take.
    @Suppress("TooGenericExceptionCaught")
    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released) return
        val start = System.nanoTime()
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(matrix)
            val timestamp = surfaceTexture.timestamp
            encoderEgl?.let { enc ->
                if (timestamp >= encodeFromNanos) {
                    egl.makeCurrent(enc)
                    renderer.draw(texture, width, height, matrix)
                    egl.setPresentationTime(enc, timestamp)
                    egl.swapBuffers(enc)
                    framesEncoded.incrementAndGet()
                }
            }
            previewEgl?.let(::drawPreview)
            framesRendered.incrementAndGet()
        } catch (e: RuntimeException) {
            error = error ?: "${e.javaClass.simpleName}: ${e.message}"
        }
        lastRenderNanos = System.nanoTime() - start
    }

    private fun drawPreview(preview: EGLSurface) {
        egl.makeCurrent(preview)
        GLES30.glViewport(0, 0, previewSize.first, previewSize.second)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val m = if (previewRotated180) {
            Matrix.multiplyMM(previewMatrix, 0, matrix, 0, ROTATE_180, 0)
            previewMatrix
        } else {
            matrix
        }
        renderer.draw(texture, previewViewport[2], previewViewport[3], m, previewViewport[0], previewViewport[1])
        if (!egl.swapBuffers(preview)) {
            // The preview went away (screen off, view detached): keep recording without it.
            egl.makeCurrent(dummy)
            egl.releaseSurface(preview)
            previewEgl = null
        }
    }

    /** Largest viewport with the video's aspect ratio inside the preview surface, centred: x, y, width, height. */
    private fun fit(size: Pair<Int, Int>): IntArray {
        val (sw, sh) = size
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
            renderer.release()
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            previewEgl?.let(egl::releaseSurface)
            encoderEgl?.let(egl::releaseSurface)
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

        /** Texture coordinates (s, t) → (1 − s, 1 − t), column-major. */
        val ROTATE_180 = floatArrayOf(-1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    }
}
