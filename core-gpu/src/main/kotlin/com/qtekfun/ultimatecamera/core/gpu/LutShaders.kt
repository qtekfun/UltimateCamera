package com.qtekfun.ultimatecamera.core.gpu

/** Where the renderer reads its input from. */
enum class InputKind {
    /** A camera or decoder frame through SurfaceTexture (`samplerExternalOES`). */
    EXTERNAL_OES,

    /** An ordinary 2D texture (tests, stills). */
    TEXTURE_2D
}

/** How the 3D LUT is interpolated on the GPU. */
enum class LutInterpolation {
    /** One linearly filtered 3D texture fetch. Fastest, but many GPUs use about 8 bits for the filter weights. */
    HARDWARE,

    /** Eight `texelFetch` and trilinear mixing in highp float: full precision for 10-bit, more texture traffic. */
    MANUAL_TRILINEAR
}

/**
 * GLSL ES 3.00 sources for the input → (optional) 3D LUT → output pass. The quad is generated from `gl_VertexID`, so no
 * vertex buffer is needed. [LutMath] is the CPU specification of what these shaders compute.
 */
object LutShaders {
    val VERTEX = """
        #version 300 es
        uniform mat4 uTexMatrix;
        out vec2 vTex;
        void main() {
            vec2 pos = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
            vTex = (uTexMatrix * vec4(pos, 0.0, 1.0)).xy;
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    fun fragment(input: InputKind, lut: LutInterpolation?): String {
        val header = when (input) {
            InputKind.EXTERNAL_OES -> "#extension GL_OES_EGL_image_external_essl3 : require\nuniform highp samplerExternalOES uInput;"
            InputKind.TEXTURE_2D -> "uniform highp sampler2D uInput;"
        }
        val apply = when (lut) {
            null -> "return c;"
            LutInterpolation.HARDWARE -> "return texture(uLut, n * uLutScale + uLutOffset).rgb;"
            LutInterpolation.MANUAL_TRILINEAR -> """
                float maxIndex = float(uLutSize - 1);
                vec3 p = n * maxIndex;
                vec3 p0 = min(floor(p), vec3(maxIndex - 1.0));
                vec3 f = p - p0;
                ivec3 i = ivec3(p0);
                vec3 c000 = texelFetch(uLut, i, 0).rgb;
                vec3 c100 = texelFetch(uLut, i + ivec3(1, 0, 0), 0).rgb;
                vec3 c010 = texelFetch(uLut, i + ivec3(0, 1, 0), 0).rgb;
                vec3 c110 = texelFetch(uLut, i + ivec3(1, 1, 0), 0).rgb;
                vec3 c001 = texelFetch(uLut, i + ivec3(0, 0, 1), 0).rgb;
                vec3 c101 = texelFetch(uLut, i + ivec3(1, 0, 1), 0).rgb;
                vec3 c011 = texelFetch(uLut, i + ivec3(0, 1, 1), 0).rgb;
                vec3 c111 = texelFetch(uLut, i + ivec3(1, 1, 1), 0).rgb;
                vec3 c0 = mix(mix(c000, c100, f.x), mix(c010, c110, f.x), f.y);
                vec3 c1 = mix(mix(c001, c101, f.x), mix(c011, c111, f.x), f.y);
                return mix(c0, c1, f.z);
            """.trimIndent()
        }
        return """
            #version 300 es
            $header
            precision highp float;
            precision highp sampler3D;
            uniform sampler3D uLut;
            uniform int uLutSize;
            uniform vec3 uLutScale;
            uniform vec3 uLutOffset;
            uniform vec3 uDomainMin;
            uniform vec3 uDomainRange;
            in vec2 vTex;
            out vec4 fragColor;
            vec3 grade(vec3 c) {
                vec3 n = clamp((c - uDomainMin) / uDomainRange, 0.0, 1.0);
                $apply
            }
            void main() {
                fragColor = vec4(grade(texture(uInput, vTex).rgb), 1.0);
            }
        """.trimIndent()
    }
}
