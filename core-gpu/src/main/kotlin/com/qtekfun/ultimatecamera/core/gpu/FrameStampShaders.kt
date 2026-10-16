package com.qtekfun.ultimatecamera.core.gpu

/**
 * GLSL ES 3.00 sources for the [FrameStamp] strip. The quad covers the whole viewport (the renderer sets the viewport
 * to the strip) and is generated from `gl_VertexID`, so no vertex buffer is needed. The fragment shader computes the
 * same cell index as [FrameStamp.cellAt]: no texture fetch, a multiply, a shift and a select per fragment.
 */
object FrameStampShaders {
    val VERTEX = """
        #version 300 es
        void main() {
            vec2 pos = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    /** `uCells`: cells 0..31 in x, 32..47 in y (bit i = cell i). `uGeometry`: viewport x origin, cells per pixel. */
    val FRAGMENT = """
        #version 300 es
        precision highp float;
        precision highp int;
        uniform highp uvec2 uCells;
        uniform vec2 uGeometry;
        out vec4 fragColor;
        void main() {
            int cell = clamp(int((gl_FragCoord.x - uGeometry.x) * uGeometry.y), 0, ${FrameStamp.CELLS - 1});
            uint word = cell < 32 ? uCells.x : uCells.y;
            float bit = float((word >> uint(cell & 31)) & 1u);
            fragColor = vec4(bit, bit, bit, 1.0);
        }
    """.trimIndent()
}
