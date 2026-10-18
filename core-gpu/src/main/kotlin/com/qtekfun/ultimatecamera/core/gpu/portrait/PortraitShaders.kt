package com.qtekfun.ultimatecamera.core.gpu.portrait

/**
 * GLSL ES 3.00 sources of the portrait kernels (D87). [gather] is [DepthBlur.gather] and [STEREO] is
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

    /**
     * The gather blur with the CoC from [source]; with [bgra] the image is an RGBA8 texture of packed ARGB ints' bytes
     * (B, G, R, A: the capture blur's reduced photo, D92), else RGB in RGB order.
     */
    fun gather(source: CocSource, bgra: Boolean = false): String {
        val main = if (bgra) {
            GATHER_MAIN.replace(
                "texelFetch(uImage, p, 0).rgb",
                "texelFetch(uImage, p, 0).bgr"
            ).replace("texelFetch(uImage, q, 0).rgb", "texelFetch(uImage, q, 0).bgr")
        } else {
            GATHER_MAIN
        }
        return HEADER + (if (source == CocSource.PLANE) COC_PLANE else COC_LIVE) + main
    }

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

    /** [GuidedUpsample.at] with the same lookup tables (R32F textures), and [Bokeh.cocPx]. */
    private val GUIDED = """
uniform highp sampler2D uDepth;
uniform highp sampler2D uGuideLow;
uniform highp sampler2D uSpatial;
uniform highp sampler2D uRange;
uniform ivec2 uDepthSize;
uniform float uFocus;

float spatialWeight(float d2) {
    return texelFetch(uSpatial, ivec2(min(int(d2 / ${GuidedUpsample.MAX_DISTANCE2} * ${GuidedUpsample.TABLE_SIZE}.0), ${GuidedUpsample.TABLE_SIZE}), 0), 0).r;
}

float rangeWeight(float difference) {
    return texelFetch(uRange, ivec2(min(int(difference * ${GuidedUpsample.TABLE_SIZE}.0), ${GuidedUpsample.TABLE_SIZE}), 0), 0).r;
}

float guided(vec2 n, float g) {
    float lx = n.x * float(uDepthSize.x) - 0.5;
    float ly = n.y * float(uDepthSize.y) - 0.5;
    int x0 = int(floor(lx));
    int y0 = int(floor(ly));
    float sum = 0.0;
    float weights = 0.0;
    for (int y = max(y0 - ${GuidedUpsample.RADIUS} + 1, 0); y <= min(y0 + ${GuidedUpsample.RADIUS}, uDepthSize.y - 1); y++) {
        for (int x = max(x0 - ${GuidedUpsample.RADIUS} + 1, 0); x <= min(x0 + ${GuidedUpsample.RADIUS}, uDepthSize.x - 1); x++) {
            float d = texelFetch(uDepth, ivec2(x, y), 0).r;
            float dx = float(x) - lx;
            float dy = float(y) - ly;
            float dg = g - texelFetch(uGuideLow, ivec2(x, y), 0).r;
            float w = d <= ${Bokeh.INVALID} ? 0.0 : spatialWeight(dx * dx + dy * dy) * rangeWeight(abs(dg));
            sum += w * d;
            weights += w;
        }
    }
    return weights > ${GuidedUpsample.MIN_WEIGHT} ? sum / weights : ${Bokeh.INVALID};
}

float cocPx(float inverse, float scale) {
    return inverse <= ${Bokeh.INVALID} || uFocus <= ${Bokeh.INVALID} ? 0.0 : scale * abs(inverse - uFocus);
}
"""

    /** The reduced CoC of the capture blur ([BlurJob.cocLow], D92) into an R32F target: guided depth at the reduced luma. */
    val COC = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uLuma;
uniform ivec2 uLow;
uniform float uCocScale;
out vec4 color;
""" + GUIDED + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 n = (vec2(p) + 0.5) / vec2(uLow);
    color = vec4(cocPx(guided(n, texelFetch(uLuma, p, 0).r), uCocScale), 0.0, 0.0, 1.0);
}
"""

    /**
     * The full-size composite of one row band ([CompositePlan.band], D92): the band of the photo is an RGBA8 texture of
     * the ARGB ints' bytes (B, G, R, A), written back in the same byte order. The CoC is the guided depth on the reduced
     * CoC's edges ([CompositePlan.edgeMask], from the CoC texture) and its bilinear value elsewhere; the pixel takes
     * [DepthBlur.blend] of the bilinear blurred colour (the gather's RGBA8 output), rounded to 8 bits as [Argb.pack];
     * untouched pixels are written back as they came.
     */
    val COMPOSITE = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uPhoto;
uniform highp sampler2D uBlurred;
uniform highp sampler2D uCoc;
uniform ivec2 uFull;
uniform ivec2 uLow;
uniform int uTop;
uniform float uScale;
uniform float uCocScale;
out vec4 color;
""" + GUIDED + """
vec4 bilinear(highp sampler2D t, ivec2 size, vec2 n) {
    float fx = clamp(n.x * float(size.x) - 0.5, 0.0, float(size.x - 1));
    float fy = clamp(n.y * float(size.y) - 0.5, 0.0, float(size.y - 1));
    int x0 = int(floor(fx));
    int y0 = int(floor(fy));
    int x1 = min(x0 + 1, size.x - 1);
    int y1 = min(y0 + 1, size.y - 1);
    float tx = fx - float(x0);
    float ty = fy - float(y0);
    vec4 p00 = texelFetch(t, ivec2(x0, y0), 0);
    vec4 p10 = texelFetch(t, ivec2(x1, y0), 0);
    vec4 p01 = texelFetch(t, ivec2(x0, y1), 0);
    vec4 p11 = texelFetch(t, ivec2(x1, y1), 0);
    vec4 top = p00 + (p10 - p00) * tx;
    vec4 bottom = p01 + (p11 - p01) * tx;
    return top + (bottom - top) * ty;
}

bool edge(ivec2 l) {
    float lo = 3.0e38;
    float hi = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            float v = texelFetch(uCoc, clamp(l + ivec2(dx, dy), ivec2(0), uLow - 1), 0).r;
            lo = min(lo, v);
            hi = max(hi, v);
        }
    }
    return hi - lo > 1.0;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 texel = texelFetch(uPhoto, p, 0);
    vec3 c = texel.bgr;
    vec2 n = vec2((float(p.x) + 0.5) / float(uFull.x), (float(uTop + p.y) + 0.5) / float(uFull.y));
    float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
    ivec2 l = clamp(ivec2(int(n.x * float(uLow.x)), int(n.y * float(uLow.y))), ivec2(0), uLow - 1);
    float coc = edge(l) ? cocPx(guided(n, luma), uCocScale) : bilinear(uCoc, uLow, n).r * uScale;
    float hi = max(2.0 * uScale, 2.0);
    float t = clamp((coc - 1.0) / (hi - 1.0), 0.0, 1.0);
    float alpha = t * t * (3.0 - 2.0 * t);
    if (alpha <= 0.0) {
        color = texel;
        return;
    }
    vec3 b = bilinear(uBlurred, uLow, n).rgb;
    vec3 mixed = clamp(floor((c + (b - c) * alpha) * 255.0 + 0.5), 0.0, 255.0) / 255.0;
    color = vec4(mixed.b, mixed.g, mixed.r, 1.0);
}
"""
}
