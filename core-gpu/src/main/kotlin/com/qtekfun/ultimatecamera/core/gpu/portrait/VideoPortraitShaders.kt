package com.qtekfun.ultimatecamera.core.gpu.portrait

/**
 * GLSL ES 3.00 of the video blur's full-size pass (D89). The reduced copy is blurred by [PortraitShaders.gather] (the
 * live-depth variant, unchanged); [COMPOSITE] then draws, over the sharp full-size frame already in the encoder
 * surface, the blurred copy with an alpha that says how much of a pixel is out of focus. That alpha comes from the CoC
 * of the 2 x 2 reduced texels around the pixel, weighted by distance and by how close each texel's luma (`uGuide`, a copy without the
 * LUT) is to the pixel's own luma: joint bilateral upsampling, so the sharp/blurred edge
 * follows the subject's outline at full resolution instead of the coarse depth's blocks.
 */
object VideoPortraitShaders {
    /** Full-viewport strip like [com.qtekfun.ultimatecamera.core.gpu.LutShaders.VERTEX], plus the output position. */
    const val VERTEX = """#version 300 es
uniform mat4 uTexMatrix;
out vec2 vTex;
out vec2 vUv;
void main() {
    vec2 pos = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vTex = (uTexMatrix * vec4(pos, 0.0, 1.0)).xy;
    vUv = pos;
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** Luma range (0..1) over which a guide texel's weight falls to e^-0.5: edges stronger than this stay sharp. */
    const val GUIDE_SIGMA = 0.08f

    /** Below this CoC radius (reduced pixels) a pixel stays sharp; one pixel above it is fully blurred. */
    const val SHARP_RADIUS = 0.5f

    val COMPOSITE = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp int;
uniform highp samplerExternalOES uCamera;
uniform highp sampler2D uBlurred;
uniform highp sampler2D uGuide;
uniform highp sampler2D uDepth;
uniform ivec2 uLowSize;
uniform ivec2 uDepthSize;
uniform float uFocus;
uniform float uScale;
uniform float uMaxRadius;
uniform float uSigma;
in vec2 vTex;
in vec2 vUv;
out vec4 color;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

float cocAt(ivec2 p) {
    vec2 uv = (vec2(p) + 0.5) / vec2(uLowSize);
    ivec2 d = clamp(ivec2(int(uv.x * float(uDepthSize.x)), int((1.0 - uv.y) * float(uDepthSize.y))), ivec2(0), uDepthSize - 1);
    float inverse = texelFetch(uDepth, d, 0).r;
    if (inverse <= ${Bokeh.INVALID} || uFocus <= ${Bokeh.INVALID}) return 0.0;
    return uScale * abs(inverse - uFocus);
}

void main() {
    float y = luma(texture(uCamera, vTex).rgb);
    vec2 low = vUv * vec2(uLowSize) - 0.5;
    ivec2 base = ivec2(floor(low));
    vec2 f = low - vec2(base);
    float sum = 0.0;
    float weights = 0.0;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            ivec2 q = clamp(base + ivec2(i, j), ivec2(0), uLowSize - 1);
            float spatial = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y) + 0.0001;
            float dl = luma(texelFetch(uGuide, q, 0).rgb) - y;
            float w = spatial * exp(-dl * dl / (2.0 * uSigma * uSigma));
            sum += cocAt(q) * w;
            weights += w;
        }
    }
    float radius = min(sum / weights / 2.0, uMaxRadius);
    float alpha = clamp(radius - $SHARP_RADIUS, 0.0, 1.0);
    if (alpha <= 0.0) discard;
    color = vec4(texture(uBlurred, vUv).rgb, alpha);
}
"""
}
