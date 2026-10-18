package com.qtekfun.ultimatecamera.core.gpu.portrait

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.gl.EglCore
import com.qtekfun.ultimatecamera.core.gpu.gl.GlException
import com.qtekfun.ultimatecamera.core.gpu.gl.GlUtil
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RgbTile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [PortraitKernels] on the GPU (D87): the gather blur ([PortraitShaders.gather]) and the block matching
 * ([PortraitShaders.STEREO]) as one fragment pass each over 32-bit float textures, read back as 8 bits (the blurred
 * copy is mixed into an 8-bit photo anyway; disparities are whole pixels). The CPU kernels are the reference
 * (`PortraitKernelsGpuTest` compares both on the phone).
 *
 * Owns its own EGL context (a 1 x 1 pbuffer): create, use and [close] on one thread.
 */
class GpuPortraitKernels : PortraitKernels {
    override val name: String = "gpu"

    private val egl = EglCore(EglColorDepth.RGBA8888, recordable = false)
    private val surface = egl.createPbufferSurface(1, 1)
    private val gather: Int
    private val stereo: Int
    private val maxSize: Int
    private val textures = IntArray(2)
    private val blurTextures = IntArray(BLUR_TEXTURES)
    private var cocProgram = 0
    private var gatherBgra = 0
    private var composite = 0
    private val target = IntArray(1)
    private val fbo = IntArray(1)
    private var targetSize = 0 to 0

    init {
        egl.makeCurrent(surface)
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
        maxSize = max[0]
        gather = GlUtil.compileProgram(PortraitShaders.VERTEX, PortraitShaders.gather(PortraitShaders.CocSource.PLANE))
        stereo = GlUtil.compileProgram(PortraitShaders.VERTEX, PortraitShaders.STEREO)
        GLES30.glGenTextures(textures.size, textures, 0)
        GLES30.glGenTextures(blurTextures.size, blurTextures, 0)
        GLES30.glGenTextures(1, target, 0)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GlUtil.checkError("portrait kernels setup")
    }

    override fun gather(image: RgbTile, coc: Plane, kernel: DiscKernel, maxRadius: Float): RgbTile {
        require(image.width == coc.width && image.height == coc.height) { "image and CoC sizes differ" }
        val w = image.width
        val h = image.height
        PortraitGpuData.problem(w, h, maxSize)?.let { throw GlException(it) }
        egl.makeCurrent(surface)
        upload(textures[0], w, h, GLES30.GL_RGBA32F, GLES30.GL_RGBA, PortraitGpuData.rgba(image))
        upload(textures[1], w, h, GLES30.GL_R32F, GLES30.GL_RED, PortraitGpuData.floats(coc.data))
        target(w, h)
        GLES30.glUseProgram(gather)
        bind(gather, "uImage", 0, textures[0])
        bind(gather, "uCoc", 1, textures[1])
        GLES30.glUniform2i(GLES30.glGetUniformLocation(gather, "uSize"), w, h)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(gather, "uKernel"), kernel.samples, kernel.packed(), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gather, "uSamples"), kernel.samples)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gather, "uMaxRadius"), maxRadius)
        val bytes = draw(w, h)
        GlUtil.checkError("portrait gather")
        return PortraitGpuData.tile(bytes, w, h)
    }

    override fun disparity(main: Plane, second: Plane, maxDisparity: Int, blockRadius: Int): Plane {
        require(main.width == second.width && main.height == second.height) { "stereo planes differ in size" }
        PortraitGpuData.problem(main.width, main.height, maxSize, maxDisparity)?.let { throw GlException(it) }
        val w = main.width
        val h = main.height
        egl.makeCurrent(surface)
        upload(textures[0], w, h, GLES30.GL_R32F, GLES30.GL_RED, PortraitGpuData.floats(main.data))
        upload(textures[1], w, h, GLES30.GL_R32F, GLES30.GL_RED, PortraitGpuData.floats(second.data))
        target(w, h)
        GLES30.glUseProgram(stereo)
        bind(stereo, "uMain", 0, textures[0])
        bind(stereo, "uSecond", 1, textures[1])
        GLES30.glUniform2i(GLES30.glGetUniformLocation(stereo, "uSize"), w, h)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(stereo, "uMaxDisparity"), maxDisparity)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(stereo, "uRadius"), blockRadius)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(stereo, "uMinContrast"), StereoDepth.MIN_CONTRAST)
        val bytes = draw(w, h)
        GlUtil.checkError("portrait block matching")
        return PortraitGpuData.disparity(bytes, w, h)
    }

    /**
     * [BlurJob.cpu] on the GPU (D92), every intermediate in textures: the reduced CoC pass ([PortraitShaders.COC], an
     * R32F target), the gather of the reduced photo uploaded as its ARGB bytes into an RGBA8 target (the 8 bits the
     * gather's read-back always had), then the full-size composite ([PortraitShaders.COMPOSITE]) over the photo in bands
     * of [COMPOSITE_ROWS] rows (upload, one draw, read-back). A band is written to [image] only after GL reported no
     * error, in order from [fromRow], so a failure part-way leaves the rest to the caller's CPU fallback.
     */
    override fun blur(job: BlurJob, image: ArgbBands, fromRow: Int) {
        if (fromRow >= image.height) return
        val w = image.width
        val bandRows = COMPOSITE_ROWS.coerceAtMost(image.height)
        val lw = job.small.width
        val lh = job.small.height
        val depth = job.guide.depth
        PortraitGpuData.problem(w, bandRows, maxSize)?.let { throw GlException(it) }
        PortraitGpuData.problem(lw, lh, maxSize)?.let { throw GlException(it) }
        PortraitGpuData.problem(depth.width, depth.height, maxSize)?.let { throw GlException(it) }
        egl.makeCurrent(surface)
        if (!floatTargets) throw GlException("no EXT_color_buffer_float")
        if (cocProgram == 0) cocProgram = GlUtil.compileProgram(PortraitShaders.VERTEX, PortraitShaders.COC)
        if (gatherBgra == 0) gatherBgra = GlUtil.compileProgram(PortraitShaders.VERTEX, PortraitShaders.gather(PortraitShaders.CocSource.PLANE, bgra = true))
        if (composite == 0) composite = GlUtil.compileProgram(PortraitShaders.VERTEX, PortraitShaders.COMPOSITE)
        uploadBlur(job, w, bandRows)
        drawCocAndGather(job)
        compositeBands(job, image, fromRow, bandRows)
    }

    /** The blur's inputs and the textures its passes draw into. */
    private fun uploadBlur(job: BlurJob, w: Int, bandRows: Int) {
        val t = blurTextures
        val lw = job.small.width
        val lh = job.small.height
        val depth = job.guide.depth
        upload(t[SMALL], lw, lh, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, PortraitGpuData.ints(job.smallArgb))
        upload(t[LUMA], lw, lh, GLES30.GL_R32F, GLES30.GL_RED, GLES30.GL_FLOAT, PortraitGpuData.floats(job.luma.data))
        upload(t[DEPTH], depth.width, depth.height, GLES30.GL_R32F, GLES30.GL_RED, GLES30.GL_FLOAT, PortraitGpuData.floats(depth.data))
        upload(t[GUIDE], depth.width, depth.height, GLES30.GL_R32F, GLES30.GL_RED, GLES30.GL_FLOAT, PortraitGpuData.floats(job.guide.guideLow.data))
        upload(t[SPATIAL], job.guide.spatialTable.size, 1, GLES30.GL_R32F, GLES30.GL_RED, GLES30.GL_FLOAT, PortraitGpuData.floats(job.guide.spatialTable))
        upload(t[RANGE], job.guide.rangeTable.size, 1, GLES30.GL_R32F, GLES30.GL_RED, GLES30.GL_FLOAT, PortraitGpuData.floats(job.guide.rangeTable))
        upload(t[COC], lw, lh, GLES30.GL_R32F, GLES30.GL_RED, GLES30.GL_FLOAT, null)
        upload(t[BLURRED], lw, lh, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        upload(t[PHOTO], w, bandRows, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        upload(t[OUT], w, bandRows, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        targetSize = 0 to 0 // The gather/stereo target is re-attached on their next use.
    }

    /** The reduced CoC, then the gather of the reduced photo with it. */
    private fun drawCocAndGather(job: BlurJob) {
        val t = blurTextures
        val lw = job.small.width
        val lh = job.small.height
        attach(t[COC])
        GLES30.glUseProgram(cocProgram)
        bindGuided(cocProgram, job)
        bind(cocProgram, "uLuma", UNIT_LUMA, t[LUMA])
        GLES30.glUniform2i(GLES30.glGetUniformLocation(cocProgram, "uLow"), lw, lh)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(cocProgram, "uCocScale"), Bokeh.cocScale(job.fNumber, lw))
        GLES30.glViewport(0, 0, lw, lh)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkError("portrait coc")

        attach(t[BLURRED])
        GLES30.glUseProgram(gatherBgra)
        bind(gatherBgra, "uImage", 0, t[SMALL])
        bind(gatherBgra, "uCoc", 1, t[COC])
        GLES30.glUniform2i(GLES30.glGetUniformLocation(gatherBgra, "uSize"), lw, lh)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(gatherBgra, "uKernel"), job.kernel.samples, job.kernel.packed(), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(gatherBgra, "uSamples"), job.kernel.samples)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(gatherBgra, "uMaxRadius"), job.maxRadius)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkError("portrait gather")
    }

    /** The full-size composite, band by band; each band is written to [image] only after GL reported no error. */
    private fun compositeBands(job: BlurJob, image: ArgbBands, fromRow: Int, bandRows: Int) {
        val t = blurTextures
        val w = image.width
        val lw = job.small.width
        val lh = job.small.height
        attach(t[OUT])
        val p = composite
        GLES30.glUseProgram(p)
        bindGuided(p, job)
        bind(p, "uPhoto", UNIT_PHOTO, t[PHOTO])
        bind(p, "uBlurred", UNIT_BLURRED, t[BLURRED])
        bind(p, "uCoc", UNIT_COC, t[COC])
        GLES30.glUniform2i(GLES30.glGetUniformLocation(p, "uFull"), w, image.height)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(p, "uLow"), lw, lh)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uScale"), w.toFloat() / lw)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p, "uCocScale"), Bokeh.cocScale(job.fNumber, w))
        val topLocation = GLES30.glGetUniformLocation(p, "uTop")
        GlUtil.checkError("portrait composite setup")
        val pixels = IntArray(w * bandRows)
        val buffer = ByteBuffer.allocateDirect(w * bandRows * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val ints = buffer.asIntBuffer()
        var top = fromRow
        while (top < image.height) {
            val rows = minOf(bandRows, image.height - top)
            val n = w * rows
            image.read(top, rows, pixels)
            // Native-order ints: an ARGB int lands as the bytes B, G, R, A, which the shader swizzles back.
            ints.clear()
            ints.put(pixels, 0, n)
            buffer.position(0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + UNIT_PHOTO)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[PHOTO])
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, rows, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            GLES30.glUniform1i(topLocation, top)
            GLES30.glViewport(0, 0, w, rows)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            buffer.position(0)
            GLES30.glReadPixels(0, 0, w, rows, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            GlUtil.checkError("portrait composite")
            ints.clear()
            ints.get(pixels, 0, n)
            image.write(top, rows, pixels)
            top += rows
        }
    }

    private val floatTargets: Boolean by lazy { "GL_EXT_color_buffer_float" in GlUtil.extensions() }

    private fun bindGuided(program: Int, job: BlurJob) {
        val t = blurTextures
        val depth = job.guide.depth
        bind(program, "uDepth", UNIT_DEPTH, t[DEPTH])
        bind(program, "uGuideLow", UNIT_GUIDE, t[GUIDE])
        bind(program, "uSpatial", UNIT_SPATIAL, t[SPATIAL])
        bind(program, "uRange", UNIT_RANGE, t[RANGE])
        GLES30.glUniform2i(GLES30.glGetUniformLocation(program, "uDepthSize"), depth.width, depth.height)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uFocus"), job.focus)
    }

    /** [texture] as the bound framebuffer's colour attachment. */
    private fun attach(texture: Int) {
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) throw GlException("portrait blur framebuffer 0x${Integer.toHexString(status)}")
    }

    private fun upload(id: Int, w: Int, h: Int, internalFormat: Int, format: Int, data: ByteBuffer) =
        upload(id, w, h, internalFormat, format, GLES30.GL_FLOAT, data)

    @Suppress("LongParameterList")
    private fun upload(id: Int, w: Int, h: Int, internalFormat: Int, format: Int, type: Int, data: ByteBuffer?) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, data)
        nearest()
    }

    private fun nearest() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun bind(program: Int, name: String, unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
    }

    /** An RGBA8 target of [w] x [h] bound to the framebuffer (re-allocated only when the size changes). */
    private fun target(w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        if (targetSize == w to h) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        nearest()
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, target[0], 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) throw GlException("portrait framebuffer 0x${Integer.toHexString(status)}")
        targetSize = w to h
    }

    private fun draw(w: Int, h: Int): ByteBuffer {
        GLES30.glViewport(0, 0, w, h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        val bytes = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        bytes.position(0)
        return bytes
    }

    @Suppress("TooGenericExceptionCaught") // Freeing a context that already failed must not throw over the caller.
    override fun close() {
        try {
            egl.makeCurrent(surface)
            GLES30.glDeleteProgram(gather)
            GLES30.glDeleteProgram(stereo)
            GLES30.glDeleteTextures(textures.size, textures, 0)
            GLES30.glDeleteTextures(blurTextures.size, blurTextures, 0)
            for (program in intArrayOf(cocProgram, gatherBgra, composite)) if (program != 0) GLES30.glDeleteProgram(program)
            GLES30.glDeleteTextures(1, target, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
        } catch (_: RuntimeException) {
            // The context is going away anyway.
        }
        egl.releaseSurface(surface)
        egl.release()
    }

    private companion object {
        /** Rows of the photo per composite draw: few round trips, a bounded band buffer (8 MB at 4080 wide). */
        const val COMPOSITE_ROWS = 512

        // Textures of the blur.
        const val SMALL = 0
        const val LUMA = 1
        const val DEPTH = 2
        const val GUIDE = 3
        const val SPATIAL = 4
        const val RANGE = 5
        const val COC = 6
        const val BLURRED = 7
        const val PHOTO = 8
        const val OUT = 9
        const val BLUR_TEXTURES = 10

        // Texture units of the CoC and composite passes (the gather uses 0 and 1).
        const val UNIT_DEPTH = 0
        const val UNIT_GUIDE = 1
        const val UNIT_SPATIAL = 2
        const val UNIT_RANGE = 3
        const val UNIT_LUMA = 4
        const val UNIT_PHOTO = 4
        const val UNIT_BLURRED = 5
        const val UNIT_COC = 6
    }
}

/** The CPU side of [GpuPortraitKernels]: texture uploads and read-backs, pure so they are JVM-tested. */
object PortraitGpuData {
    private const val MAX = 255f
    private const val CHANNELS = 4

    /**
     * Why the GPU cannot take a [w] x [h] input on a context with [maxTextureSize] (or a block matching up to
     * [maxDisparity], whose 8-bit output reserves [PortraitShaders.STEREO_INVALID]); null when it can. The caller then
     * falls back to the CPU instead of drawing garbage.
     */
    fun problem(w: Int, h: Int, maxTextureSize: Int, maxDisparity: Int = 0): String? = when {
        w <= 0 || h <= 0 -> "empty input ${w}x$h"
        maxOf(w, h) > maxTextureSize -> "GL_MAX_TEXTURE_SIZE $maxTextureSize < ${maxOf(w, h)}"
        maxDisparity >= PortraitShaders.STEREO_INVALID -> "disparity $maxDisparity does not fit 8 bits"
        else -> null
    }

    /** [tile] as interleaved RGBA floats (alpha 1), for a GL_RGBA32F upload. */
    fun rgba(tile: RgbTile): ByteBuffer {
        val n = tile.width * tile.height
        val buffer = ByteBuffer.allocateDirect(n * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val floats = buffer.asFloatBuffer()
        for (i in 0 until n) floats.put(tile.r.data[i]).put(tile.g.data[i]).put(tile.b.data[i]).put(1f)
        return buffer
    }

    /** Packed ARGB [pixels] as a direct buffer in native order (the bytes B, G, R, A per pixel), for a GL_RGBA8 upload. */
    fun ints(pixels: IntArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(pixels.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        buffer.asIntBuffer().put(pixels)
        return buffer
    }

    /** [values] as a direct float buffer, for a GL_R32F upload. */
    fun floats(values: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(values)
        return buffer
    }

    /** RGBA8 pixels read back (row 0 first) as a tile in 0..1. */
    fun tile(bytes: ByteBuffer, w: Int, h: Int): RgbTile {
        val out = RgbTile(w, h)
        for (i in 0 until w * h) {
            out.r.data[i] = byte(bytes, i * CHANNELS) / MAX
            out.g.data[i] = byte(bytes, i * CHANNELS + 1) / MAX
            out.b.data[i] = byte(bytes, i * CHANNELS + 2) / MAX
        }
        return out
    }

    /** The red channel of [PortraitShaders.STEREO]'s output as disparities, [PortraitShaders.STEREO_INVALID] → no depth. */
    fun disparity(bytes: ByteBuffer, w: Int, h: Int): Plane {
        val out = Plane(w, h)
        for (i in 0 until w * h) {
            val d = byte(bytes, i * CHANNELS)
            out.data[i] = if (d == PortraitShaders.STEREO_INVALID) Bokeh.INVALID else d.toFloat()
        }
        return out
    }

    private fun byte(bytes: ByteBuffer, index: Int): Int = bytes.get(index).toInt() and 0xFF
}
