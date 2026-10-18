package com.qtekfun.ultimatecamera.core.gpu.photo

import android.opengl.EGL14
import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.gl.EglCore
import com.qtekfun.ultimatecamera.core.gpu.gl.GlException
import com.qtekfun.ultimatecamera.core.gpu.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One fragment program run over an ARGB_8888 image in strips of rows (D90): each strip is read into an int array
 * ([read]), uploaded as an RGBA8 texture (the little-endian ARGB ints arrive as B, G, R, A: shaders read `.bgr`),
 * drawn into an RGBA8 target of the same size and read back into the same array for [write] (shaders write
 * `vec4(b, g, r, a)`). Memory on both sides is one strip. Extra inputs (tables, small planes, a 3D LUT) are bound with
 * [table], [plane] and [lut3d] before [run].
 *
 * Owns its own EGL context (a 1 x 1 pbuffer): create, use and [close] on one thread. Whatever context the thread had
 * current is made current again on [close].
 */
class GpuStripPass(fragment: String, private val stripRows: Int = PhotoGpuData.STRIP_ROWS) : AutoCloseable {
    private val previous = EglState.capture()
    private val egl = EglCore(EglColorDepth.RGBA8888, recordable = false)
    private val surface = egl.createPbufferSurface(1, 1)
    val program: Int
    val maxTextureSize: Int
    private val extra = ArrayList<Int>()
    private val strip = IntArray(2)
    private val fbo = IntArray(1)
    private var stripWidth = 0

    init {
        egl.makeCurrent(surface)
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
        maxTextureSize = max[0]
        program = GlUtil.compileProgram(VERTEX, fragment)
        GLES30.glGenTextures(strip.size, strip, 0)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GLES30.glUseProgram(program)
        GlUtil.checkError("strip pass setup")
    }

    fun uniform(name: String): Int = GLES30.glGetUniformLocation(program, name)

    /** Makes this pass's program current again (after another program ran on its context). */
    fun use() {
        egl.makeCurrent(surface)
        GLES30.glUseProgram(program)
    }

    /** A float table of any length as a [PhotoGpuData.TABLE_WIDTH]-wide R32F texture on texture [unit] for sampler [name]. */
    fun table(unit: Int, name: String, values: FloatArray) {
        val h = PhotoGpuData.tableRows(values.size)
        floatTexture(unit, name, PhotoGpuData.TABLE_WIDTH, h, PhotoGpuData.padded(values, PhotoGpuData.TABLE_WIDTH * h))
    }

    /** A [width] x [height] float plane as an R32F texture on [unit] for sampler [name]. */
    fun plane(unit: Int, name: String, width: Int, height: Int, values: FloatArray) {
        if (maxOf(width, height) > maxTextureSize) throw GlException("plane ${width}x$height over GL_MAX_TEXTURE_SIZE $maxTextureSize")
        floatTexture(unit, name, width, height, values)
    }

    private fun floatTexture(unit: Int, name: String, width: Int, height: Int, values: FloatArray) {
        val id = newTexture(unit, GLES30.GL_TEXTURE_2D)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, width, height, 0, GLES30.GL_RED, GLES30.GL_FLOAT, PhotoGpuData.floats(values))
        nearest(GLES30.GL_TEXTURE_2D)
        GLES30.glUniform1i(uniform(name), unit)
        GlUtil.checkError("texture $name ($id)")
    }

    /** A [size]³ lattice of RGB floats (red fastest, as [com.qtekfun.ultimatecamera.core.gpu.CubeLut]) on [unit]. */
    fun lut3d(unit: Int, name: String, size: Int, rgb: FloatArray) {
        newTexture(unit, GLES30.GL_TEXTURE_3D)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB32F, size, size, size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, PhotoGpuData.floats(rgb))
        nearest(GLES30.GL_TEXTURE_3D)
        GLES30.glUniform1i(uniform(name), unit)
        GlUtil.checkError("3D texture $name")
    }

    private fun newTexture(unit: Int, target: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        extra += id[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, id[0])
        return id[0]
    }

    private fun nearest(target: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /**
     * Runs the program over a [width] x [height] image: [read] fills `rows` ARGB rows from row `y` into the array,
     * [write] takes them back processed, strip by strip from the top, each only after GL reported no error. If it
     * throws, the rows already written are done and the rest untouched, so a CPU fallback can carry on from there.
     * The program sees its strip on unit 0 (`uImage`) and the strip's first row in `uStripY`.
     */
    fun run(width: Int, height: Int, read: (IntArray, Int, Int) -> Unit, write: (IntArray, Int, Int) -> Unit) {
        PhotoGpuData.problem(width, height, maxTextureSize)?.let { throw GlException(it) }
        val rows = minOf(stripRows, height)
        use()
        allocate(width, rows)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        val pixels = IntArray(width * rows)
        val bytes = ByteBuffer.allocateDirect(width * rows * 4).order(ByteOrder.LITTLE_ENDIAN)
        val ints = bytes.asIntBuffer()
        val stripY = uniform("uStripY")
        GLES30.glUniform1i(uniform("uImage"), 0)
        var y = 0
        while (y < height) {
            val n = minOf(rows, height - y)
            read(pixels, y, n)
            ints.position(0)
            ints.put(pixels, 0, width * n)
            bytes.position(0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, strip[0])
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, width, n, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            GLES30.glUniform1i(stripY, y)
            GLES30.glViewport(0, 0, width, n)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            bytes.position(0)
            GLES30.glReadPixels(0, 0, width, n, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            GlUtil.checkError("strip at row $y") // Before write: a failed strip never reaches the image.
            ints.position(0)
            ints.get(pixels, 0, width * n)
            write(pixels, y, n)
            y += n
        }
    }

    /** The input strip texture and the output target, [width] x [rows] each (re-allocated only when the width changes). */
    private fun allocate(width: Int, rows: Int) {
        if (stripWidth == width) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0) // Units 1+ hold the pass's tables: never rebind those.
        for (id in strip) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, rows, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            nearest(GLES30.GL_TEXTURE_2D)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, strip[1], 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) throw GlException("strip framebuffer 0x${Integer.toHexString(status)}")
        stripWidth = width
    }

    @Suppress("TooGenericExceptionCaught") // Freeing a context that already failed must not throw over the caller.
    override fun close() {
        try {
            egl.makeCurrent(surface)
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteTextures(strip.size, strip, 0)
            if (extra.isNotEmpty()) GLES30.glDeleteTextures(extra.size, extra.toIntArray(), 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
        } catch (_: RuntimeException) {
            // The context is going away anyway.
        }
        egl.releaseSurface(surface)
        egl.release()
        previous.restore()
    }

    companion object {
        /** A full-viewport triangle strip from gl_VertexID, no vertex buffers. */
        const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID & 1) * 2 - 1), float((gl_VertexID >> 1) * 2 - 1));
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

        /** Shared fragment header: the strip, its first row, the output and 8-bit rounding as the CPU paths round. */
        const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uImage;
uniform int uStripY;
out vec4 color;

// x * 255 + 0.5 truncated, as the CPU's (v * 255f + 0.5f).toInt(): the RGBA8 target then stores it exactly.
float q8(float v) { return floor(clamp(v, 0.0, 1.0) * 255.0 + 0.5) / 255.0; }

// The strip pixel as ARGB colour and alpha (the upload delivers B, G, R, A).
vec4 pixel() { return texelFetch(uImage, ivec2(gl_FragCoord.xy), 0); }

// Writes an RGB result with alpha so the read-back bytes are B, G, R, A again (little-endian ARGB ints).
void emit(vec3 rgb, float alpha) { color = vec4(q8(rgb.b), q8(rgb.g), q8(rgb.r), alpha); }

// A float table laid out in rows of 64 (PhotoGpuData.TABLE_WIDTH), linearly interpolated at f in 0..size.
float tableAt(sampler2D t, int i) { return texelFetch(t, ivec2(i & 63, i >> 6), 0).r; }
float lookup(sampler2D t, float f, int size) {
    int i = min(int(f), size - 1);
    float a = tableAt(t, i);
    return a + (tableAt(t, i + 1) - a) * (f - float(i));
}
"""
    }
}

/** The EGL context, surfaces and display current on this thread, to put back after a pass with its own context. */
internal class EglState private constructor(
    private val display: android.opengl.EGLDisplay,
    private val draw: android.opengl.EGLSurface,
    private val read: android.opengl.EGLSurface,
    private val context: android.opengl.EGLContext
) {
    fun restore() {
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglMakeCurrent(display, draw, read, context)
    }

    companion object {
        fun capture(): EglState = EglState(
            EGL14.eglGetCurrentDisplay(),
            EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW),
            EGL14.eglGetCurrentSurface(EGL14.EGL_READ),
            EGL14.eglGetCurrentContext()
        )
    }
}

/** The CPU side of [GpuStripPass]: sizes, table layout and buffers, pure so they are JVM-tested. */
object PhotoGpuData {
    /** Rows per strip: 4080 x 256 x 4 bytes = 4 MB per side. */
    const val STRIP_ROWS = 256

    /** Tables are laid out [TABLE_WIDTH] floats per row, so any length fits the 2048-texel minimum of GLES 3. */
    const val TABLE_WIDTH = 64

    fun tableRows(length: Int): Int = (length + TABLE_WIDTH - 1) / TABLE_WIDTH

    /** [values] followed by zeros up to [length]. */
    fun padded(values: FloatArray, length: Int): FloatArray = if (values.size == length) values else values.copyOf(length)

    /** Why the GPU cannot take a [width] x [height] image on a context with [maxTextureSize]; null when it can. */
    fun problem(width: Int, height: Int, maxTextureSize: Int): String? = when {
        width <= 0 || height <= 0 -> "empty image ${width}x$height"
        width > maxTextureSize -> "GL_MAX_TEXTURE_SIZE $maxTextureSize < width $width"
        else -> null
    }

    /** [values] as a direct native-order buffer for a float upload. */
    fun floats(values: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(values)
        buffer.position(0)
        return buffer
    }
}
