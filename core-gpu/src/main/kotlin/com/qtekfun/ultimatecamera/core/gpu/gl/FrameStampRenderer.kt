package com.qtekfun.ultimatecamera.core.gpu.gl

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.FrameStamp
import com.qtekfun.ultimatecamera.core.gpu.FrameStampShaders

/**
 * Draws the [FrameStamp] strip for a counter over the top [FrameStamp.STRIP_HEIGHT_FRACTION] of a viewport in the
 * current framebuffer. Create, use and [release] it on the thread that owns the GL context; call [draw] right after the
 * LUT pass of the proxy frame, before the swap, with the same viewport.
 *
 * Cost per frame, chosen for modest phones:
 * - CPU: [FrameStamp.cellBits] (a 16-step CRC loop on registers), two uniform updates and one draw call of a
 *   4-vertex strip without vertex buffers. No allocations, no GL object creation, no state queries (`glGet*` can
 *   stall the driver).
 * - GPU: the viewport is limited to the strip, so only `width × round(0.04 × height)` fragments run: 37 120 at 720p and
 *   82 560 at 1080p, about 4 % of the LUT pass, each one a multiply, a shift and a select with no texture fetch. Being
 *   in the same render pass as the LUT draw, it adds no extra framebuffer load/store on tile-based GPUs; the strip just
 *   overwrites pixels already in the tile.
 *
 * State it changes: the current program, and the viewport, which it restores to ([x], [y], `width`, `height`) on return.
 * It expects blending, depth and stencil tests off (as in the LUT pass) and writes alpha 1.
 */
class FrameStampRenderer {
    private val program = GlUtil.compileProgram(FrameStampShaders.VERTEX, FrameStampShaders.FRAGMENT)
    private val cellsLocation = GLES30.glGetUniformLocation(program, "uCells")
    private val geometryLocation = GLES30.glGetUniformLocation(program, "uGeometry")

    /**
     * Draws the strip for [counter] (taken modulo 2^16) at the top of the [width]×[height] viewport at ([x], [y]), in
     * GL's bottom-up coordinates: the strip covers rows `y + height - stripHeight` to `y + height - 1`, which is the
     * top of the image an encoder or a display surface receives.
     */
    fun draw(counter: Int, width: Int, height: Int, x: Int = 0, y: Int = 0) {
        val stripHeight = FrameStamp.stripHeight(height)
        val cells = FrameStamp.cellBits(counter)
        GLES30.glViewport(x, y + height - stripHeight, width, stripHeight)
        GLES30.glUseProgram(program)
        GLES30.glUniform2ui(cellsLocation, cells.toInt(), (cells ushr 32).toInt())
        // Same float maths as FrameStamp.cellAt, so the CPU reference and the GPU agree on every cell boundary.
        GLES30.glUniform2f(geometryLocation, x.toFloat(), FrameStamp.CELLS.toFloat() / width)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glViewport(x, y, width, height)
    }

    fun release() {
        GLES30.glDeleteProgram(program)
    }
}
