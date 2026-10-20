package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.SceneWhite
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Samples the camera frame for the scene white balance: every [EVERY_NTH_FRAME] frames the frame is drawn as it comes
 * (no LUT, no Log) into a [WIDTH]×[HEIGHT] RGBA8 target and read back asynchronously (pixel-pack buffers and fences, as
 * the scopes do), so the render thread never waits; finished reads are turned into [SceneWhite.illuminant] and
 * reported. Costs one tiny draw and a 9 KB read a few times a second. GL thread only.
 */
internal class SceneColorSampler(private val hlg: Boolean) {
    private val target = RenderTarget(WIDTH, HEIGHT, GLES30.GL_RGBA8)
    private val renderer = LutRenderer(InputKind.EXTERNAL_OES, LutInterpolation.HARDWARE)
    private val buffers = IntArray(SLOTS)
    private val fences = LongArray(SLOTS)
    private val pending = BooleanArray(SLOTS)
    private val ratios = FloatArray(2)
    private val white = SceneWhite()
    private var next = 0
    private var frame = 0

    init {
        GLES30.glGenBuffers(SLOTS, buffers, 0)
        for (b in buffers) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, b)
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, BYTES, null, GLES30.GL_STREAM_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Called once per camera frame with its texture and transform; [report] gets red and blue over green. */
    fun onFrame(texture: Int, texMatrix: FloatArray, report: (Float, Float) -> Unit) {
        poll(report)
        if (frame++ % EVERY_NTH_FRAME != 0 || !target.complete) return
        val slot = next
        if (pending[slot]) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        renderer.draw(texture, WIDTH, HEIGHT, texMatrix)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffers[slot])
        GLES30.glReadPixels(0, 0, WIDTH, HEIGHT, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        fences[slot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        pending[slot] = true
        next = (slot + 1) % SLOTS
    }

    private fun poll(report: (Float, Float) -> Unit) {
        for (k in 0 until SLOTS) {
            val slot = (next + k) % SLOTS
            if (pending[slot] && signalled(slot) && read(slot)) report(ratios[0], ratios[1])
        }
    }

    private fun signalled(slot: Int): Boolean {
        val status = GLES30.glClientWaitSync(fences[slot], 0, 0)
        return status == GLES30.GL_ALREADY_SIGNALED || status == GLES30.GL_CONDITION_SATISFIED
    }

    /** Reads a finished slot into [ratios]; false when the picture had too little usable light. */
    private fun read(slot: Int): Boolean {
        GLES30.glDeleteSync(fences[slot])
        pending[slot] = false
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffers[slot])
        val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, BYTES, GLES30.GL_MAP_READ_BIT) as ByteBuffer?
        val got = mapped != null && white.illuminant(mapped.order(ByteOrder.nativeOrder()), WIDTH * HEIGHT, hlg, ratios)
        if (mapped != null) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        return got
    }

    fun release() {
        for (slot in 0 until SLOTS) if (pending[slot]) GLES30.glDeleteSync(fences[slot])
        GLES30.glDeleteBuffers(SLOTS, buffers, 0)
        renderer.release()
        target.release()
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 36
        const val BYTES = WIDTH * HEIGHT * 4
        const val SLOTS = 3

        /** About 4 measurements a second at 60 fps (2 at 30): the white balance moves smoothly, not per frame. */
        const val EVERY_NTH_FRAME = 15
    }
}
