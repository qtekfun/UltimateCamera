package com.qtekfun.ultimatecamera.core.gpu.hdr

/** GLSL ES 3.00 sources of [GpuExposureFusion]. Same maths as [FastExposureFusion] and [GainMapMath]. */
internal object FusionShaders {
    /** A full-viewport triangle strip from gl_VertexID, no vertex buffers. */
    const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID & 1) * 2 - 1), float((gl_VertexID >> 1) * 2 - 1));
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
uniform ivec2 uSize;
out vec4 color;

// I420 frame at full resolution: Y, then U and V at half size (BT.601 full range, as the camera's YUV_420_888).
vec3 rgbAt(sampler2D ty, sampler2D tu, sampler2D tv, ivec2 s) {
    float y = texelFetch(ty, s, 0).r * 255.0;
    ivec2 c = min(s / 2, uSize / 2 - 1);
    float u = texelFetch(tu, c, 0).r * 255.0 - 128.0;
    float v = texelFetch(tv, c, 0).r * 255.0 - 128.0;
    return clamp(vec3(y + 1.402 * v, y - 0.344136 * u - 0.714136 * v, y + 1.772 * u) / 255.0, 0.0, 1.0);
}
"""

    /** One frame (units bound to uY0, uU0, uV0) averaged over factor × factor blocks into a low-resolution target. */
    const val DOWNSCALE = HEADER + """
uniform sampler2D uY0;
uniform sampler2D uU0;
uniform sampler2D uV0;
uniform int uFactor;
void main() {
    ivec2 o = ivec2(gl_FragCoord.xy) * uFactor;
    ivec2 e = min(o + uFactor, uSize);
    vec3 sum = vec3(0.0);
    for (int y = o.y; y < e.y; y++) {
        for (int x = o.x; x < e.x; x++) sum += rgbAt(uY0, uU0, uV0, ivec2(x, y));
    }
    color = vec4(sum / float((e.x - o.x) * (e.y - o.y)), 1.0);
}
"""

    private const val BLEND = HEADER + """
uniform sampler2D uY0;
uniform sampler2D uU0;
uniform sampler2D uV0;
uniform sampler2D uY1;
uniform sampler2D uU1;
uniform sampler2D uV1;
uniform sampler2D uY2;
uniform sampler2D uU2;
uniform sampler2D uV2;
uniform sampler2D uFused;
uniform sampler2D uLow0;
uniform sampler2D uLow1;
uniform sampler2D uLow2;
uniform ivec2 uShift0;
uniform ivec2 uShift1;
uniform ivec2 uShift2;
uniform ivec2 uOut;
uniform int uStripY;
uniform int uTurn;
uniform vec2 uLowScale;

// Upright output pixel -> sensor pixel (inverse of the clockwise turn).
ivec2 source(ivec2 o) {
    if (uTurn == 90) return ivec2(o.y, uSize.y - 1 - o.x);
    if (uTurn == 180) return ivec2(uSize.x - 1 - o.x, uSize.y - 1 - o.y);
    if (uTurn == 270) return ivec2(uSize.x - 1 - o.y, o.x);
    return o;
}

ivec2 at(ivec2 s, ivec2 shift) { return clamp(s + shift, ivec2(0), uSize - 1); }

// Fused base, upsampled, plus each frame's detail times its upsampled normalised weight (FastExposureFusion).
vec3 blend(ivec2 s, out vec3 i0, out vec3 i1, out vec3 i2) {
    vec2 uv = (vec2(s) + 0.5) * uLowScale;
    vec3 c = texture(uFused, uv).rgb;
    i0 = rgbAt(uY0, uU0, uV0, at(s, uShift0));
    vec4 l0 = texture(uLow0, uv);
    c += l0.a * (i0 - l0.rgb);
    i1 = rgbAt(uY1, uU1, uV1, at(s, uShift1));
    vec4 l1 = texture(uLow1, uv);
    c += l1.a * (i1 - l1.rgb);
    i2 = rgbAt(uY2, uU2, uV2, at(s, uShift2));
    vec4 l2 = texture(uLow2, uv);
    c += l2.a * (i2 - l2.rgb);
    return clamp(c, 0.0, 1.0);
}
"""

    /** A strip of upright output rows, written as B, G, R, A bytes so they read back as Android ARGB ints. */
    const val OUTPUT = BLEND + """
void main() {
    ivec2 o = ivec2(gl_FragCoord.xy) + ivec2(0, uStripY);
    vec3 i0;
    vec3 i1;
    vec3 i2;
    vec3 c = blend(source(o), i0, i1, i2);
    color = vec4(c.b, c.g, c.r, 1.0);
}
"""

    /** The Ultra HDR gain map at 1/4 size, in alpha (reads back as the ALPHA_8 value in the top byte). */
    const val GAIN = BLEND + """
uniform vec3 uExposure;
uniform int uDarkest;
uniform float uMaxRatio;
float lin(float v) { return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4); }
float conf(float v) { float u = (2.0 * v - 1.0) * (2.0 * v - 1.0); float u3 = u * u * u; return max(1.0 - u3 * u3, 0.0); }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
    ivec2 o = min(ivec2(gl_FragCoord.xy) * 4, uOut - 1);
    vec3 i0;
    vec3 i1;
    vec3 i2;
    vec3 c = blend(source(o), i0, i1, i2);
    vec3 v = vec3(luma(i0), luma(i1), luma(i2));
    vec3 w = vec3(conf(v.x), conf(v.y), conf(v.z));
    vec3 l = vec3(lin(v.x), lin(v.y), lin(v.z)) / uExposure;
    float ws = w.x + w.y + w.z;
    float radiance = ws > 0.0 ? dot(w, l) / ws : (uDarkest == 0 ? l.x : (uDarkest == 1 ? l.y : l.z));
    float sdr = lin(luma(c));
    float gain = sdr <= 1e-4 ? 1.0 : clamp(radiance / sdr, 1.0, uMaxRatio);
    color = vec4(0.0, 0.0, 0.0, log(gain) / log(uMaxRatio));
}
"""
}
