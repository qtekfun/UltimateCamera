package com.qtekfun.ultimatecamera.core.gpu.portrait

/**
 * GLSL ES 3.00 sources of the portrait kernels (D83). [gather] is [DepthBlur.gather] and [STEREO] is
 * [StereoDepth.disparity], line for line: same disc samples (uniforms), same rounding of sample positions, same
 * weights, same offset order and texture check. Everything is read with `texelFetch` (no filtering), so 32-bit float
 * textures work on every GLES 3.0 GPU.
 */
object PortraitShaders {
    /** A full-viewport triangle strip from gl_VertexID, no vertex buffers. */
    const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID & 1) * 2 - 1), float((gl_VertexID >> 1) * 2 - 1));
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

    /** Where the CoC of a pixel comes from: the uploaded plane (capture) or the live depth map (preview). */
    enum class CocSource { PLANE, LIVE_DEPTH }

    private val HEADER = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uImage;
uniform ivec2 uSize;
uniform vec3 uKernel[${DiscKernel.MAX_SAMPLES}];
uniform int uSamples;
uniform float uMaxRadius;
out vec4 color;
"""

    /** CoC diameter per pixel from an R32F plane the size of the image ([DepthBlur.gather]'s `coc`). */
    private const val COC_PLANE = """
uniform highp sampler2D uCoc;
float cocAt(ivec2 p) { return texelFetch(uCoc, p, 0).r; }
"""

    /**
     * CoC from the live inverse-depth map (R32F, its row 0 the top of the picture, while the camera frame drawn into the
     * framebuffer has its top at the last row): [Bokeh.cocPx], nearest depth sample, no depth = no blur.
     */
    private val COC_LIVE = """
uniform highp sampler2D uDepth;
uniform ivec2 uDepthSize;
uniform float uFocus;
uniform float uScale;
float cocAt(ivec2 p) {
    vec2 uv = (vec2(p) + 0.5) / vec2(uSize);
    ivec2 d = clamp(ivec2(int(uv.x * float(uDepthSize.x)), int((1.0 - uv.y) * float(uDepthSize.y))), ivec2(0), uDepthSize - 1);
    float inverse = texelFetch(uDepth, d, 0).r;
    if (inverse <= ${Bokeh.INVALID} || uFocus <= ${Bokeh.INVALID}) return 0.0;
    return uScale * abs(inverse - uFocus);
}
"""

    private const val GATHER_MAIN = """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float radius = min(cocAt(p) / 2.0, uMaxRadius);
    vec3 sum = texelFetch(uImage, p, 0).rgb;
    float weights = 1.0;
    if (radius >= 0.5) {
        for (int s = 0; s < uSamples; s++) {
            vec3 k = uKernel[s];
            ivec2 q = clamp(ivec2(floor(vec2(p) + k.xy * radius + 0.5)), ivec2(0), uSize - 1);
            float reach = min(cocAt(q) / 2.0, uMaxRadius);
            float w = clamp(reach - k.z * radius + 1.0, 0.0, 1.0);
            sum += texelFetch(uImage, q, 0).rgb * w;
            weights += w;
        }
    }
    color = vec4(sum / weights, 1.0);
}
"""

    /** The gather blur with the CoC from [source]. */
    fun gather(source: CocSource): String = HEADER + (if (source == CocSource.PLANE) COC_PLANE else COC_LIVE) + GATHER_MAIN

    /** Value of the red channel that marks "no depth" in [STEREO]'s 8-bit output. */
    const val STEREO_INVALID = 255

    /**
     * Block matching: per pixel the offset ([StereoDepth.offset] order) with the lowest mean absolute difference over the
     * block (clamped at the borders: the mean of the block's pixels inside the image, as the CPU's normalised box sums),
     * disparity in the red channel as d / 255, [STEREO_INVALID] where the best cost is not [StereoDepth.MIN_CONTRAST]
     * below the worst.
     */
    const val STEREO = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uMain;
uniform highp sampler2D uSecond;
uniform ivec2 uSize;
uniform int uMaxDisparity;
uniform int uRadius;
uniform float uMinContrast;
out vec4 color;

ivec2 offsetOf(int i) {
    if (i == 0) return ivec2(0);
    int d = (i - 1) / 4 + 1;
    int k = (i - 1) - ((i - 1) / 4) * 4;
    if (k == 0) return ivec2(d, 0);
    if (k == 1) return ivec2(-d, 0);
    if (k == 2) return ivec2(0, d);
    return ivec2(0, -d);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 lo = max(p - uRadius, ivec2(0));
    ivec2 hi = min(p + uRadius, uSize - 1);
    float area = float((hi.x - lo.x + 1) * (hi.y - lo.y + 1));
    float best = 3.0e38;
    float worst = 0.0;
    int bestD = 0;
    int count = 1 + 4 * uMaxDisparity;
    for (int i = 0; i < count; i++) {
        ivec2 o = offsetOf(i);
        float sum = 0.0;
        for (int y = lo.y; y <= hi.y; y++) {
            for (int x = lo.x; x <= hi.x; x++) {
                ivec2 q = ivec2(x, y);
                sum += abs(texelFetch(uMain, q, 0).r - texelFetch(uSecond, clamp(q + o, ivec2(0), uSize - 1), 0).r);
            }
        }
        float cost = sum / area;
        if (cost < best) {
            best = cost;
            bestD = max(abs(o.x), abs(o.y));
        }
        if (cost > worst) worst = cost;
    }
    float value = worst - best < uMinContrast ? 1.0 : float(bestD) / 255.0;
    color = vec4(value, 0.0, 0.0, 1.0);
}
"""
}
