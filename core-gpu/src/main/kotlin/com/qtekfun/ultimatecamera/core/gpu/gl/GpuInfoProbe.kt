package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES30
import kotlinx.serialization.Serializable

@Serializable
data class GpuInfo(
    val renderer: String,
    val vendor: String,
    val version: String,
    val glslVersion: String,
    val maxTextureSize: Int,
    val max3dTextureSize: Int,
    /** A 10-bit EGL config exists at all, and one that encoders accept as input (EGL_RECORDABLE_ANDROID). */
    val rgba1010102: Boolean,
    val rgba1010102Recordable: Boolean,
    val bt2020HlgColorspace: Boolean,
    /** Extensions that decide the 10-bit path: raw YUV sampling, float render targets for precise meters/tests. */
    val extensions: Map<String, Boolean>
)

/** Reads the GPU facts the Phase 0 report needs. Run it on a thread with no current EGL context. */
object GpuInfoProbe {
    private val interesting = listOf(
        "GL_EXT_YUV_target",
        "GL_OES_EGL_image_external_essl3",
        "GL_EXT_color_buffer_float",
        "GL_EXT_color_buffer_half_float",
        "GL_OES_texture_float_linear",
        "GL_EXT_texture_norm16"
    )

    fun probe(): GpuInfo {
        val egl = EglCore(EglColorDepth.RGBA8888, recordable = false)
        val surface = egl.createPbufferSurface(1, 1)
        try {
            egl.makeCurrent(surface)
            val gl = GlUtil.extensions()
            val max = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
            val max2d = max[0]
            GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, max, 0)
            val tenBit = runCatching { EglCore(EglColorDepth.RGBA1010102, recordable = true) }.getOrNull()
            val info = GpuInfo(
                renderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty(),
                vendor = GLES30.glGetString(GLES30.GL_VENDOR).orEmpty(),
                version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty(),
                glslVersion = GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION).orEmpty(),
                maxTextureSize = max2d,
                max3dTextureSize = max[0],
                rgba1010102 = tenBit != null,
                rgba1010102Recordable = tenBit?.isRecordable == true,
                bt2020HlgColorspace = egl.supportsBt2020Hlg,
                extensions = interesting.associateWith { it in gl }
            )
            tenBit?.release()
            egl.makeCurrent(surface)
            return info
        } finally {
            egl.releaseSurface(surface)
            egl.release()
        }
    }
}
