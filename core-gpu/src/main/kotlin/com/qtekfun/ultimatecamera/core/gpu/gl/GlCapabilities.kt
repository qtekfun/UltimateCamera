package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES30

/** What the current GL context offers to the meters. Query once per context (it calls `glGet*`), never per frame. */
data class GlCapabilities(val majorVersion: Int, val minorVersion: Int, val extensions: Set<String>) {
    private fun atLeast(major: Int, minor: Int) = majorVersion > major || (majorVersion == major && minorVersion >= minor)

    /** RGBA16F is colour-renderable and blendable: core in GLES 3.2, or either colour-buffer float extension. */
    val halfFloatColorBuffer: Boolean
        get() = atLeast(3, 2) || "GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions

    /** GPU timer queries (`GL_TIME_ELAPSED_EXT`). */
    val timerQuery: Boolean get() = "GL_EXT_disjoint_timer_query" in extensions

    /** Compute shaders exist (GLES 3.1); the meters do not need them (see `ScopeRenderer`). */
    val computeShaders: Boolean get() = atLeast(3, 1)

    companion object {
        fun current(): GlCapabilities {
            val value = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, value, 0)
            val major = value[0]
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, value, 0)
            return GlCapabilities(major, value[0], GlUtil.extensions())
        }
    }
}
