package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/** Colour depth of the EGL surfaces a [EglCore] creates. */
enum class EglColorDepth(val red: Int, val green: Int, val blue: Int, val alpha: Int) {
    RGBA8888(8, 8, 8, 8),

    /** 10-bit per channel, needed to feed a 10-bit (HEVC Main10) encoder input surface without losing precision. */
    RGBA1010102(10, 10, 10, 2)
}

/** Thrown when EGL cannot provide what was asked for (no matching config, a failed call). */
class EglException(message: String) : RuntimeException(message)

/**
 * One EGL display + OpenGL ES 3 context. Not thread-safe: create, use and [release] it on a single thread (the render
 * thread). The config is chosen with exactly the requested channel sizes, because eglChooseConfig sorts deeper configs
 * first and would otherwise hand a 10-bit config to an 8-bit pipeline.
 */
class EglCore(val depth: EglColorDepth, recordable: Boolean = true) {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val config: EGLConfig
    val context: EGLContext

    /** Whether the chosen config has EGL_RECORDABLE_ANDROID (required by some encoders' input surfaces). */
    val isRecordable: Boolean
    val extensions: Set<String>

    init {
        if (display == EGL14.EGL_NO_DISPLAY) throw EglException("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw EglException("eglInitialize failed: ${eglError()}")
        extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
        val recordableConfig = if (recordable) chooseConfig(recordable = true) else null
        isRecordable = recordableConfig != null
        config = recordableConfig ?: chooseConfig(recordable = false)
            ?: throw EglException("no EGL config with ${depth.name} and OpenGL ES 3")
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0
        )
        if (context == EGL14.EGL_NO_CONTEXT) throw EglException("eglCreateContext failed: ${eglError()}")
    }

    /** True when the driver can tag a surface as BT.2020 HLG (EGL_EXT_gl_colorspace_bt2020_hlg). */
    val supportsBt2020Hlg: Boolean get() = "EGL_EXT_gl_colorspace_bt2020_hlg" in extensions

    /**
     * A window surface for [surface] (an encoder input, a SurfaceView). With [hlg], and when the driver supports it, the
     * surface is tagged BT.2020 HLG so the consumer knows the shader writes HLG-encoded values.
     */
    fun createWindowSurface(surface: Surface, hlg: Boolean = false): EGLSurface {
        val attribs = if (hlg && supportsBt2020Hlg) {
            intArrayOf(EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE)
        } else {
            intArrayOf(EGL14.EGL_NONE)
        }
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attribs, 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) throw EglException("eglCreateWindowSurface failed: ${eglError()}")
        return eglSurface
    }

    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val eglSurface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE),
            0
        )
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) throw EglException("eglCreatePbufferSurface failed: ${eglError()}")
        return eglSurface
    }

    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw EglException("eglMakeCurrent failed: ${eglError()}")
    }

    /** Sets the timestamp the consumer (encoder) sees for the next swap, in nanoseconds. */
    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        // No eglTerminate: the default display is shared with the UI toolkit's renderer in this process.
    }

    /** Width and height of [surface] in pixels. */
    fun size(surface: EGLSurface): Pair<Int, Int> {
        val value = IntArray(1)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, value, 0)
        val width = value[0]
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, value, 0)
        return width to value[0]
    }

    private fun chooseConfig(recordable: Boolean): EGLConfig? {
        val attribs = mutableListOf(
            EGL14.EGL_RED_SIZE, depth.red,
            EGL14.EGL_GREEN_SIZE, depth.green,
            EGL14.EGL_BLUE_SIZE, depth.blue,
            EGL14.EGL_ALPHA_SIZE, depth.alpha,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT
        )
        if (recordable) attribs += listOf(EGL_RECORDABLE_ANDROID, 1)
        attribs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(MAX_CONFIGS)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs.toIntArray(), 0, configs, 0, configs.size, count, 0)) return null
        return configs.take(count[0]).filterNotNull().firstOrNull { c ->
            attr(c, EGL14.EGL_RED_SIZE) == depth.red && attr(c, EGL14.EGL_GREEN_SIZE) == depth.green &&
                attr(c, EGL14.EGL_BLUE_SIZE) == depth.blue && attr(c, EGL14.EGL_ALPHA_SIZE) == depth.alpha
        }
    }

    private fun attr(config: EGLConfig, attribute: Int): Int {
        val value = IntArray(1)
        EGL14.eglGetConfigAttrib(display, config, attribute, value, 0)
        return value[0]
    }

    private fun eglError(): String = "0x" + Integer.toHexString(EGL14.eglGetError())

    private companion object {
        const val MAX_CONFIGS = 64
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val EGL_OPENGL_ES3_BIT_KHR = 0x40
        const val EGL_GL_COLORSPACE_KHR = 0x309D
        const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
    }
}
