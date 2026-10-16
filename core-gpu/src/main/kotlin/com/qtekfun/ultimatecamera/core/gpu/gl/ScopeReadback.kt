package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Reads integer counts out of a count texture: the pixel format, its size and the decoding. */
internal class CountReader(private val target: RenderTarget, private val format: AccumulationFormat) {
    val readFormat = GLES30.GL_RGBA
    val readType: Int
    private val bytesPerChannel: Int
    val byteCount: Int

    init {
        readType = if (format == AccumulationFormat.UNORM8) {
            GLES30.GL_UNSIGNED_BYTE
        } else {
            // The implementation's preferred type for this float target: half float if it says so, else float.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
            val value = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_IMPLEMENTATION_COLOR_READ_FORMAT, value, 0)
            val implFormat = value[0]
            GLES30.glGetIntegerv(GLES30.GL_IMPLEMENTATION_COLOR_READ_TYPE, value, 0)
            if (implFormat == GLES30.GL_RGBA && value[0] == GLES30.GL_HALF_FLOAT) GLES30.GL_HALF_FLOAT else GLES30.GL_FLOAT
        }
        bytesPerChannel = when (readType) {
            GLES30.GL_UNSIGNED_BYTE -> 1
            GLES30.GL_HALF_FLOAT -> 2
            else -> 4
        }
        byteCount = target.width * target.height * 4 * bytesPerChannel
    }

    /** Decodes [byteCount] bytes of [buffer] into one integer count per channel. Allocation-free. */
    fun decode(buffer: ByteBuffer, out: IntArray) {
        val channels = target.width * target.height * 4
        for (i in 0 until channels) {
            out[i] = when (bytesPerChannel) {
                1 -> buffer.get(i).toInt() and 0xFF
                2 -> halfToFloat(buffer.getShort(i * 2).toInt()).roundToInt()
                else -> buffer.getFloat(i * 4).roundToInt()
            }
        }
    }

    /**
     * IEEE half to float for the values a count texel can hold: zero and normal numbers (counts are integers, so no
     * denormals, infinities or NaNs occur).
     */
    private fun halfToFloat(bits: Int): Float {
        if ((bits and 0x7C00) == 0) return 0f
        return Float.fromBits(((bits and 0x8000) shl 16) or (((bits and 0x7C00) + 0x1C000) shl 13) or ((bits and 0x03FF) shl 13))
    }

    /** Synchronous read for tests and probes (stalls; allocates). */
    fun readBlocking(out: IntArray) {
        val buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, target.width, target.height, readFormat, readType, buffer)
        decode(buffer, out)
    }
}

/**
 * Asynchronous histogram readback: [request] (with the histogram target bound, right after it is drawn) starts a
 * `glReadPixels` into one of [SLOTS] pixel-pack buffers and inserts a fence; [poll] maps only buffers whose fence has
 * already signalled (`glClientWaitSync` with a zero timeout), so the render thread never waits for the GPU. When all
 * slots are in flight a request is skipped.
 */
internal class HistogramReadback(target: RenderTarget, private val layers: Int, format: AccumulationFormat) {
    private val reader = CountReader(target, format)
    private val width = target.width
    private val height = target.height
    private val buffers = IntArray(SLOTS)
    private val fences = LongArray(SLOTS)
    private val pending = BooleanArray(SLOTS)
    private val layered = IntArray(target.width * target.height * 4)
    private var next = 0

    init {
        GLES30.glGenBuffers(SLOTS, buffers, 0)
        for (b in buffers) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, b)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, reader.byteCount, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    fun request() {
        val slot = next
        if (pending[slot]) return
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffers[slot])
        GLES30.glReadPixels(0, 0, width, height, reader.readFormat, reader.readType, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        fences[slot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        pending[slot] = true
        next = (slot + 1) % SLOTS
    }

    /** Sums the newest finished readback into [out] (`LEVELS × 4`); false when none finished since the last call. */
    fun poll(out: IntArray): Boolean {
        var got = false
        // Oldest first, so that [out] ends with the newest finished result.
        for (k in 0 until SLOTS) {
            val slot = (next + k) % SLOTS
            if (pending[slot] && signalled(slot) && read(slot, out)) got = true
        }
        return got
    }

    private fun signalled(slot: Int): Boolean {
        val status = GLES30.glClientWaitSync(fences[slot], 0, 0)
        return status == GLES30.GL_ALREADY_SIGNALED || status == GLES30.GL_CONDITION_SATISFIED
    }

    private fun read(slot: Int, out: IntArray): Boolean {
        GLES30.glDeleteSync(fences[slot])
        pending[slot] = false
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffers[slot])
        val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, reader.byteCount, GLES30.GL_MAP_READ_BIT) as ByteBuffer?
        if (mapped != null) {
            reader.decode(mapped.order(ByteOrder.nativeOrder()), layered)
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            ScopeMath.sumLayers(layered, layers, out)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        return mapped != null
    }

    fun release() {
        for (slot in 0 until SLOTS) if (pending[slot]) GLES30.glDeleteSync(fences[slot])
        GLES30.glDeleteBuffers(SLOTS, buffers, 0)
    }

    companion object {
        const val SLOTS = 3
    }
}
