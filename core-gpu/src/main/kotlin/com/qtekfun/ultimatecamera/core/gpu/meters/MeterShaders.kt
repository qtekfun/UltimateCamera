package com.qtekfun.ultimatecamera.core.gpu.meters

import com.qtekfun.ultimatecamera.core.gpu.InputKind

/**
 * GLSL ES 3.00 sources of the monitor overlay and the scopes. Only GLES 3.0 features are used (vertex texture fetch,
 * integer maths, instancing, `gl_VertexID`), so they run on every phone the app supports; no vertex buffers.
 * [MonitorOverlayMath] and [ScopeMath] are the CPU specification of what they compute.
 */
object MeterShaders {
    /** Full-viewport quad without texture coordinates (displays use `gl_FragCoord`). */
    val QUAD_VERTEX = """
        #version 300 es
        void main() {
            vec2 pos = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
            gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    private const val LUMA = "const vec3 kLuma = vec3(${Luma.KR}, ${Luma.KG}, ${Luma.KB});"

    /** Peaking (bit 0), zebra A (bit 1), zebra B (bit 2) and false colour (bit 3) in one pass over the content rect. */
    fun overlayFragment(input: InputKind): String {
        val header = when (input) {
            InputKind.EXTERNAL_OES -> "#extension GL_OES_EGL_image_external_essl3 : require\nuniform highp samplerExternalOES uInput;"
            InputKind.TEXTURE_2D -> "uniform highp sampler2D uInput;"
        }
        // Concatenated rather than interpolated: a multi-line header would defeat trimIndent and indent #version.
        return "#version 300 es\n$header\n" + """
            precision highp float;
            precision highp int;
            uniform vec2 uStepX;
            uniform vec2 uStepY;
            uniform int uFlags;
            uniform vec3 uPeakColor;
            uniform float uPeakThreshold;
            uniform vec2 uZebraA;
            uniform vec2 uZebraB;
            uniform vec3 uZebraAColor;
            uniform vec3 uZebraBColor;
            uniform int uStripe;
            uniform int uPhase;
            uniform ivec2 uOrigin;
            uniform int uContentHeight;
            uniform int uBandCount;
            uniform vec2 uBandRange[${FalseColorSettings.MAX_BANDS}];
            uniform vec3 uBandColor[${FalseColorSettings.MAX_BANDS}];
            in vec2 vTex;
            out vec4 fragColor;
            $LUMA
            float lumaAt(vec2 t) { return dot(texture(uInput, t).rgb, kLuma); }
            void main() {
                vec3 c = texture(uInput, vTex).rgb;
                float y = dot(c, kLuma);
                if ((uFlags & 8) != 0) {
                    vec3 f = vec3(y);
                    for (int i = 0; i < uBandCount; i++) {
                        if (y >= uBandRange[i].x && y < uBandRange[i].y) {
                            f = uBandColor[i];
                            break;
                        }
                    }
                    c = f;
                }
                ivec2 p = ivec2(gl_FragCoord.xy) - uOrigin;
                if ((uFlags & 2) != 0 && y >= uZebraA.x && y <= uZebraA.y && (((p.x + p.y + uPhase) / uStripe) & 1) == 0) c = uZebraAColor;
                if ((uFlags & 4) != 0 && y >= uZebraB.x && y <= uZebraB.y &&
                    (((p.x + uContentHeight - 1 - p.y + uPhase) / uStripe) & 1) == 0) c = uZebraBColor;
                if ((uFlags & 1) != 0) {
                    float e = abs(lumaAt(vTex + uStepX) - lumaAt(vTex - uStepX)) + abs(lumaAt(vTex + uStepY) - lumaAt(vTex - uStepY));
                    if (e > uPeakThreshold) c = uPeakColor;
                }
                fragColor = vec4(c, 1.0);
            }
        """.trimIndent()
    }

    /** One instance per guide rectangle (pixels, viewport-relative); only covered pixels are shaded. */
    val GUIDE_VERTEX = """
        #version 300 es
        uniform vec4 uRects[${GuideLayout.MAX_RECTS}];
        uniform vec4 uColors[${GuideLayout.MAX_RECTS}];
        uniform vec2 uViewport;
        flat out vec4 vColor;
        void main() {
            vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
            vec4 r = uRects[gl_InstanceID];
            gl_Position = vec4((r.xy + corner * r.zw) / uViewport * 2.0 - 1.0, 0.0, 1.0);
            vColor = uColors[gl_InstanceID];
        }
    """.trimIndent()

    val GUIDE_FRAGMENT = """
        #version 300 es
        precision mediump float;
        flat in vec4 vColor;
        out vec4 fragColor;
        void main() { fragColor = vColor; }
    """.trimIndent()

    private val SCATTER_COMMON = """
        #version 300 es
        precision highp float;
        precision highp int;
        uniform highp sampler2D uAnalysis;
        uniform int uWidth;
        flat out vec4 vMask;
        ivec3 codeAt(int i) { return ivec3(texelFetch(uAnalysis, ivec2(i % uWidth, i / uWidth), 0).rgb * 255.0 + 0.5); }
        int lumaScaled(ivec3 c) { return 2126 * c.r + 7152 * c.g + 722 * c.b; }
        int levelOf(ivec3 c, int channel) {
            return channel == 0 ? c.r : (channel == 1 ? c.g : (channel == 2 ? c.b : (lumaScaled(c) + 5000) / 10000));
        }
    """.trimIndent()

    /**
     * Histogram scatter: vertex = analysis sample, instance = channel (from `uFirstChannel`). Sample `i` goes to layer
     * `i % uLayers` so that no texel exceeds the target's capacity; the point lands on texel (level, layer).
     */
    val HISTOGRAM_VERTEX = SCATTER_COMMON + "\n" + """
        uniform int uLayers;
        uniform int uFirstChannel;
        void main() {
            int channel = gl_InstanceID + uFirstChannel;
            int level = levelOf(codeAt(gl_VertexID), channel);
            int layer = gl_VertexID % uLayers;
            gl_Position = vec4((float(level) + 0.5) / 128.0 - 1.0, (float(layer) + 0.5) / float(uLayers) * 2.0 - 1.0, 0.0, 1.0);
            gl_PointSize = 1.0;
            vMask = vec4(equal(ivec4(channel), ivec4(0, 1, 2, 3)));
        }
    """.trimIndent()

    /** Waveform scatter: the point lands on texel (column of the sample, level). */
    val WAVEFORM_VERTEX = SCATTER_COMMON + "\n" + """
        uniform int uFirstChannel;
        void main() {
            int channel = gl_InstanceID + uFirstChannel;
            int level = levelOf(codeAt(gl_VertexID), channel);
            int column = gl_VertexID % uWidth;
            gl_Position = vec4((float(column) + 0.5) / float(uWidth) * 2.0 - 1.0, (float(level) + 0.5) / 128.0 - 1.0, 0.0, 1.0);
            gl_PointSize = 1.0;
            vMask = vec4(equal(ivec4(channel), ivec4(0, 1, 2, 3)));
        }
    """.trimIndent()

    /** Vectorscope scatter: exact integer Cb/Cr cell, see [ScopeMath]. */
    val VECTORSCOPE_VERTEX = SCATTER_COMMON + "\n" + """
        uniform int uSize;
        uint cellOf(int numerator, int denominator) {
            return min(uint(2 * numerator + denominator) * uint(uSize) / uint(2 * denominator), uint(uSize - 1));
        }
        void main() {
            ivec3 c = codeAt(gl_VertexID);
            int y = lumaScaled(c);
            uint cx = cellOf(c.b * 10000 - y, ${ScopeBins.CB_DENOMINATOR});
            uint cy = cellOf(c.r * 10000 - y, ${ScopeBins.CR_DENOMINATOR});
            gl_Position = vec4((vec2(float(cx), float(cy)) + 0.5) / float(uSize) * 2.0 - 1.0, 0.0, 1.0);
            gl_PointSize = 1.0;
            vMask = vec4(1.0, 0.0, 0.0, 0.0);
        }
    """.trimIndent()

    /** Every scatter point adds `uIncrement` (1 in half float, 1/255 in RGBA8) to its channel; blending is ONE, ONE. */
    val SCATTER_FRAGMENT = """
        #version 300 es
        precision highp float;
        uniform float uIncrement;
        flat in vec4 vMask;
        out vec4 fragColor;
        void main() { fragColor = vMask * uIncrement; }
    """.trimIndent()

    private val DISPLAY_COMMON = """
        #version 300 es
        precision highp float;
        precision highp int;
        uniform highp sampler2D uCounts;
        uniform float uUnit;
        uniform ivec2 uOrigin;
        uniform ivec2 uSize;
        uniform vec3 uColors[5];
        uniform float uBackground;
        out vec4 fragColor;
        vec4 countsAt(ivec2 texel) { return floor(texelFetch(uCounts, texel, 0) * uUnit + 0.5); }
    """.trimIndent()

    /** Output is premultiplied (blend ONE, ONE_MINUS_SRC_ALPHA): scope colour over a `uBackground` black panel. */
    val HISTOGRAM_DISPLAY = DISPLAY_COMMON + "\n" + """
        uniform int uLayers;
        uniform float uScale;
        uniform int uLumaMode;
        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy) - uOrigin;
            int bin = p.x * ${ScopeMath.LEVELS} / uSize.x;
            vec4 sum = vec4(0.0);
            for (int l = 0; l < uLayers; l++) sum += countsAt(ivec2(bin, l));
            vec4 h = min(vec4(1.0), sum * uScale);
            float level = (float(p.y) + 0.5) / float(uSize.y);
            vec3 rgb = vec3(0.0);
            float lit = 0.0;
            if (uLumaMode == 1) {
                if (level < h.a) { rgb = uColors[3]; lit = 1.0; }
            } else {
                for (int c = 0; c < 3; c++) {
                    if (level < h[c]) { rgb = min(vec3(1.0), rgb + uColors[c]); lit = 1.0; }
                }
            }
            fragColor = vec4(rgb, max(uBackground, lit));
        }
    """.trimIndent()

    val WAVEFORM_DISPLAY = DISPLAY_COMMON + "\n" + """
        uniform float uGain;
        uniform int uMode;
        uniform int uAnalysisWidth;
        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy) - uOrigin;
            int level = p.y * ${ScopeMath.LEVELS} / uSize.y;
            vec3 rgb = vec3(0.0);
            float m = 0.0;
            if (uMode == 2) {
                int panel = p.x * 3 / uSize.x;
                int x0 = panel * uSize.x / 3;
                int x1 = (panel + 1) * uSize.x / 3;
                vec4 c = countsAt(ivec2((p.x - x0) * uAnalysisWidth / (x1 - x0), level));
                m = min(1.0, c[panel] * uGain);
                rgb = min(vec3(1.0), uColors[panel] * m);
            } else {
                vec4 i = min(vec4(1.0), countsAt(ivec2(p.x * uAnalysisWidth / uSize.x, level)) * uGain);
                if (uMode == 0) {
                    rgb = min(vec3(1.0), uColors[3] * i.a);
                    m = i.a;
                } else {
                    rgb = min(vec3(1.0), rgb + uColors[0] * i.r);
                    rgb = min(vec3(1.0), rgb + uColors[1] * i.g);
                    rgb = min(vec3(1.0), rgb + uColors[2] * i.b);
                    m = max(i.r, max(i.g, i.b));
                }
            }
            fragColor = vec4(rgb, uBackground + (1.0 - uBackground) * m);
        }
    """.trimIndent()

    val VECTORSCOPE_DISPLAY = DISPLAY_COMMON + "\n" + """
        uniform float uGain;
        uniform int uGrid;
        void main() {
            ivec2 p = ivec2(gl_FragCoord.xy) - uOrigin;
            float i = min(1.0, countsAt(ivec2(p.x * uGrid / uSize.x, p.y * uGrid / uSize.y)).r * uGain);
            fragColor = vec4(min(vec3(1.0), uColors[4] * i), uBackground + (1.0 - uBackground) * i);
        }
    """.trimIndent()
}
