package com.qtekfun.ultimatecamera.feature.spike.pipeline

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.view.Surface
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.FrameStamp
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.gl.EglCore
import com.qtekfun.ultimatecamera.core.gpu.gl.FrameStampRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.LutRenderer

/**
 * The GPU half of the spike pipeline: camera frames arrive in a SurfaceTexture, go through the LUT pass into the encoder
 * surface (with the camera timestamp) and, if there is one, into the preview surface.
 *
 * Construct, use and [release] on the GL thread behind [handler]. Per frame there are no allocations: timings go into
 * preallocated arrays sized by [capacity].
 */
internal class GlPipeline(
    depth: EglColorDepth,
    encoderSurface: Surface,
    previewSurface: Surface?,
    lut: CubeLut?,
    interpolation: LutInterpolation?,
    private val width: Int,
    private val height: Int,
    hlg: Boolean,
    capacity: Int,
    handler: Handler,
    private val proxy: ProxyOutput? = null
) : SurfaceTexture.OnFrameAvailableListener {
    private val egl = EglCore(depth, recordable = true)
    val eglRecordable = egl.isRecordable
    val hlgTagged = hlg && egl.supportsBt2020Hlg
    private val encoderEgl: EGLSurface = egl.createWindowSurface(encoderSurface, hlg)

    @Volatile var previewError: String? = null
        private set

    @Volatile var error: String? = null
        private set

    private var previewEgl: EGLSurface? = null
    private var previewSize = 0 to 0
    private var previewViewport = IntArray(4)

    private val renderer: LutRenderer
    private val texture: Int
    private val surfaceTexture: SurfaceTexture
    val cameraSurface: Surface
    private val matrix = FloatArray(16)

    @Volatile var measuring = false
    val timestamps = TimestampLog(capacity)
    private val renderNanos = LongArray(capacity)

    @Volatile private var renderCount = 0

    @Volatile var framesTotal = 0
        private set

    @Volatile private var released = false

    init {
        egl.makeCurrent(encoderEgl)
        renderer = LutRenderer(InputKind.EXTERNAL_OES, interpolation ?: LutInterpolation.HARDWARE)
        renderer.setLut(if (interpolation != null) lut else null)
        texture = LutRenderer.createExternalTexture()
        surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener(this@GlPipeline, handler)
        }
        cameraSurface = Surface(surfaceTexture)
        previewSurface?.let { surface ->
            runCatching { egl.createWindowSurface(surface) }
                .onSuccess { p ->
                    previewEgl = p
                    previewSize = egl.size(p)
                    previewViewport = fit(previewSize)
                    // A full display queue must never block the render thread (and with it the encoder).
                    egl.makeCurrent(p)
                    egl.setSwapInterval(0)
                    egl.makeCurrent(encoderEgl)
                }
                .onFailure { previewError = it.message }
        }
        proxy?.let { out ->
            runCatching {
                val p = egl.createWindowSurface(out.surface)
                // The WebRTC capturer must never stall the camera pipeline: non-blocking swaps, like the preview.
                egl.makeCurrent(p)
                egl.setSwapInterval(0)
                stamp = FrameStampRenderer()
                proxyEgl = p
                egl.makeCurrent(encoderEgl)
            }.onFailure { proxyError = it.message }
        }
    }

    // Render-thread boundary: a GL/EGL failure is recorded in the result instead of killing the app mid-test.
    @Suppress("TooGenericExceptionCaught")
    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released) return
        val start = System.nanoTime()
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(matrix)
            val timestamp = surfaceTexture.timestamp
            egl.makeCurrent(encoderEgl)
            renderer.draw(texture, width, height, matrix)
            egl.setPresentationTime(encoderEgl, timestamp)
            egl.swapBuffers(encoderEgl)
            drawPreview()
            drawProxy()
            framesTotal++
            if (measuring) {
                timestamps.add(timestamp)
                val n = renderCount
                if (n < renderNanos.size) {
                    renderNanos[n] = System.nanoTime() - start
                    renderCount = n + 1
                }
            }
        } catch (e: RuntimeException) {
            if (error == null) error = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun drawPreview() {
        val preview = previewEgl ?: return
        egl.makeCurrent(preview)
        GLES30.glViewport(0, 0, previewSize.first, previewSize.second)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        renderer.draw(texture, previewViewport[2], previewViewport[3], matrix, previewViewport[0], previewViewport[1])
        if (!egl.swapBuffers(preview)) {
            // The preview went away (screen off, view detached): keep recording without it.
            previewError = previewError ?: "preview swap failed"
            egl.releaseSurface(preview)
            previewEgl = null
        }
    }

    private var proxyEgl: EGLSurface? = null
    private var stamp: FrameStampRenderer? = null
    private var proxyCounter = 0

    @Volatile var proxyError: String? = null
        private set

    private fun drawProxy() {
        val out = proxy ?: return
        if (framesTotal % out.everyNthFrame != 0) return
        val surface = proxyEgl ?: return
        egl.makeCurrent(surface)
        renderer.draw(texture, out.width, out.height, matrix)
        stamp?.draw(proxyCounter, out.width, out.height)
        if (egl.swapBuffers(surface)) {
            out.onSubmitted(proxyCounter, System.nanoTime())
            proxyCounter++
        } else {
            proxyError = proxyError ?: "proxy swap failed"
            egl.releaseSurface(surface)
            proxyEgl = null
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

    fun renderTimes(): LongArray = renderNanos.copyOf(renderCount)

    fun release() {
        released = true
        surfaceTexture.setOnFrameAvailableListener(null)
        cameraSurface.release()
        surfaceTexture.release()
        renderer.release()
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        previewEgl?.let(egl::releaseSurface)
        proxyEgl?.let(egl::releaseSurface)
        stamp?.release()
        egl.releaseSurface(encoderEgl)
        egl.release()
    }
}
