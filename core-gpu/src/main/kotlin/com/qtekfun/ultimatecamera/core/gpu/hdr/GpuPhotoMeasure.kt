package com.qtekfun.ultimatecamera.core.gpu.hdr

import android.opengl.GLES30
import android.opengl.GLES31
import com.qtekfun.ultimatecamera.core.gpu.gl.GlException
import com.qtekfun.ultimatecamera.core.gpu.gl.GlUtil
import com.qtekfun.ultimatecamera.core.gpu.photo.GpuStripPass
import com.qtekfun.ultimatecamera.core.gpu.photo.PhotoGpuData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pass 1 of [PhotoLook] on the GPU (D90; 0.4 s on the CPU for the first 12 MP photo after start): a GLES 3.1 compute
 * shader, one work group per low block, sums the block's log luma (the same table as [LogLuma]) and colour and adds
 * every pixel to the 1024-bin luma histogram with atomics. The photo is uploaded in strips of whole low rows. Sums are
 * in float in another order than the CPU's, so the low image differs in the last bits; the histogram bins are the
 * same but for luma values that land exactly on a bin edge. Throws on a context without compute shaders (the caller
 * then measures on the CPU). Runs on the context of the [GpuStripPass] it is made for.
 */
internal class GpuPhotoMeasure {
    private val program = GlUtil.compileCompute(SHADER)
    private val textures = IntArray(2)
    private val buffers = IntArray(2)

    init {
        GLES30.glGenTextures(textures.size, textures, 0)
        GLES30.glGenBuffers(buffers.size, buffers, 0)
        GlUtil.checkError("measure setup")
    }

    /** Pass 1 over the [width] x [height] ARGB image [read] gives, with [PhotoLook]'s block [factor]. */
    fun measure(width: Int, height: Int, factor: Int, read: (IntArray, Int, Int) -> Unit): PhotoLook.Measured {
        val lowW = (width + factor - 1) / factor
        val lowH = (height + factor - 1) / factor
        val rows = factor * maxOf(1, PhotoGpuData.STRIP_ROWS / factor)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, rows, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        nearest()
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uImage"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[1])
        val log = PhotoGpuData.padded(LogLuma.table(), PhotoGpuData.TABLE_WIDTH * PhotoGpuData.tableRows(LogLuma.SIZE + 1))
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, PhotoGpuData.TABLE_WIDTH, log.size / PhotoGpuData.TABLE_WIDTH, 0, GLES30.GL_RED, GLES30.GL_FLOAT,
            PhotoGpuData.floats(
                log
            )
        )
        nearest()
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLog"), 1)
        storage(0, PhotoLook.BINS * Int.SIZE_BYTES)
        storage(1, lowW * lowH * 4 * Float.SIZE_BYTES)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uFactor"), factor)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLowW"), lowW)
        val size = GLES30.glGetUniformLocation(program, "uSize")
        val lowY = GLES30.glGetUniformLocation(program, "uLowY")
        val pixels = IntArray(width * rows)
        val bytes = ByteBuffer.allocateDirect(width * rows * 4).order(ByteOrder.LITTLE_ENDIAN)
        val ints = bytes.asIntBuffer()
        var y = 0
        while (y < height) {
            val n = minOf(rows, height - y)
            read(pixels, y, n)
            ints.position(0)
            ints.put(pixels, 0, width * n)
            bytes.position(0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, width, n, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
            GLES30.glUniform2i(size, width, n)
            GLES30.glUniform1i(lowY, y / factor)
            GLES31.glDispatchCompute(lowW, (n + factor - 1) / factor, 1)
            y += n
        }
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
        val histogram = IntArray(PhotoLook.BINS).also { fetch(0, it.size * Int.SIZE_BYTES).asIntBuffer().get(it) }
        val low = FloatArray(lowW * lowH * 4).also { fetch(1, it.size * Float.SIZE_BYTES).asFloatBuffer().get(it) }
        GlUtil.checkError("measure")
        return PhotoLook.Measured.of(lowW, lowH, low, histogram)
    }

    private fun nearest() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    }

    /** Zeroed storage buffer [index] of [bytes], bound to binding [index]. */
    private fun storage(index: Int, bytes: Int) {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[index])
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()), GLES30.GL_DYNAMIC_READ)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, index, buffers[index])
    }

    private fun fetch(index: Int, bytes: Int): ByteBuffer {
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[index])
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
            ?: throw GlException("cannot map measure buffer $index")
        val copy = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        copy.put(mapped.order(ByteOrder.nativeOrder()))
        copy.position(0)
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        return copy
    }

    @Suppress("TooGenericExceptionCaught") // The context is going away with the pass anyway.
    fun release() {
        try {
            GLES30.glDeleteProgram(program)
            GLES30.glDeleteTextures(textures.size, textures, 0)
            GLES30.glDeleteBuffers(buffers.size, buffers, 0)
        } catch (_: RuntimeException) {
            // Nothing more to free.
        }
    }

    private companion object {
        const val GROUP = 64

        val SHADER = """#version 310 es
precision highp float;
precision highp int;
precision highp sampler2D;
layout(local_size_x = $GROUP) in;
uniform sampler2D uImage;
uniform sampler2D uLog;
uniform int uFactor;
uniform ivec2 uSize;
uniform int uLowY;
uniform int uLowW;
layout(std430, binding = 0) buffer Histogram { uint hist[]; };
layout(std430, binding = 1) buffer Low { vec4 low[]; };
shared vec4 part[$GROUP];

float tableAt(int i) { return texelFetch(uLog, ivec2(i & 63, i >> 6), 0).r; }
float logLuma(float v) {
    float f = clamp(v, 0.0, 1.0) * ${LogLuma.SIZE}.0;
    int i = min(int(f), ${LogLuma.SIZE - 1});
    float a = tableAt(i);
    return a + (tableAt(i + 1) - a) * (f - float(i));
}

void main() {
    int x0 = int(gl_WorkGroupID.x) * uFactor;
    int y0 = int(gl_WorkGroupID.y) * uFactor;
    int bw = min(uFactor, uSize.x - x0);
    int bh = min(uFactor, uSize.y - y0);
    uint id = gl_LocalInvocationID.x;
    vec4 s = vec4(0.0);
    for (int k = int(id); k < bw * bh; k += $GROUP) {
        vec3 c = texelFetch(uImage, ivec2(x0 + k % bw, y0 + k / bw), 0).bgr;
        float l = 0.299 * c.r + 0.587 * c.g + 0.114 * c.b;
        atomicAdd(hist[clamp(int(l * ${PhotoLook.BINS - 1}.0 + 0.5), 0, ${PhotoLook.BINS - 1})], 1u);
        s += vec4(logLuma(l), c);
    }
    part[id] = s;
    barrier();
    for (uint stride = ${GROUP / 2}u; stride > 0u; stride >>= 1u) {
        if (id < stride) part[id] += part[id + stride];
        barrier();
    }
    if (id == 0u) low[(uLowY + int(gl_WorkGroupID.y)) * uLowW + int(gl_WorkGroupID.x)] = part[0] / float(bw * bh);
}
"""
    }
}
