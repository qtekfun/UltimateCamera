package com.qtekfun.ultimatecamera.core.gpu.hdr

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.photo.GpuStripPass

/**
 * The full-size pass of [PhotoLook] on the GPU (D90; on the Pixel 8 the CPU pass took 1.3 s of the 1.9 s a 12 MP
 * photo needed), and the edge-aware base on the small image ([guided], [GpuGuidedFilter]). Pass 1 and the tone stay
 * on the CPU ([PhotoLook.prepare]); [render] uploads the plan (the
 * guided filter's `a` and `b` planes, the log-luma, tone and finish tables, the scalars of [ToneMap] and the
 * saturation gain) and runs [GpuPhotoLookShader.FRAGMENT], the CPU loop line for line with the same tables and the
 * same rounding, over the photo in strips ([GpuStripPass]). Output within one 8-bit level of the CPU's
 * (`PhotoLookGpuTest` on the phone).
 *
 * Create, use and close on one thread.
 */
class GpuPhotoLook : AutoCloseable {
    private val pass = GpuStripPass(GpuPhotoLookShader.FRAGMENT)
    private var filter: GpuGuidedFilter? = null
    private var measurer: GpuPhotoMeasure? = null

    /** Pass 1 of [look] over the ARGB image [read] gives ([GpuPhotoMeasure]); throws without compute shaders. */
    fun measure(look: PhotoLook, width: Int, height: Int, read: (IntArray, Int, Int) -> Unit): PhotoLook.Measured {
        pass.use()
        val m = measurer ?: GpuPhotoMeasure().also { measurer = it }
        return m.measure(width, height, look.factor, read)
    }

    /** [GuidedBase.of] on this context ([GpuGuidedFilter]); throws when the GPU cannot (no float targets). */
    fun guided(low: Plane, radius: Int, eps: Float): GuidedBase {
        pass.use()
        val f = filter ?: GpuGuidedFilter().also { filter = it }
        return f.of(low, radius, eps)
    }

    /** Renders [plan] over the ARGB image [read] gives, `rows` rows from `y`, and hands the result to [write]. */
    fun render(plan: LookPlan, read: (IntArray, Int, Int) -> Unit, write: (IntArray, Int, Int) -> Unit) {
        pass.use()
        val u = GpuPhotoLookShader.uniforms(plan)
        pass.table(UNIT_LOG, "uLog", LogLuma.table())
        pass.table(UNIT_TONE, "uTone", plan.tone.outTable())
        pass.table(UNIT_FINISH, "uFinish", plan.tone.finishTable())
        pass.plane(UNIT_A, "uA", plan.lowW, plan.lowH, plan.base.a.data)
        pass.plane(UNIT_B, "uB", plan.lowW, plan.lowH, plan.base.b.data)
        GLES30.glUniform2i(pass.uniform("uLowSize"), plan.lowW, plan.lowH)
        GLES30.glUniform1i(pass.uniform("uFactor"), plan.factor)
        GLES30.glUniform1fv(pass.uniform("uF"), u.size, u, 0)
        pass.run(plan.width, plan.height, read, write)
    }

    override fun close() {
        pass.use()
        filter?.release()
        measurer?.release()
        pass.close()
    }

    private companion object {
        const val UNIT_LOG = 1
        const val UNIT_TONE = 2
        const val UNIT_FINISH = 3
        const val UNIT_A = 4
        const val UNIT_B = 5
    }
}

/** The shader of [GpuPhotoLook] and its scalar uniforms (pure, JVM-tested). */
object GpuPhotoLookShader {
    /** Indices into the `uF` uniform array. */
    enum class F {
        WHITE_GAIN,
        FLOOR,
        FLOOR_TOP,
        PIVOT,
        STRENGTH,
        MAX_LIFT,
        DETAIL,
        OUT_MIN,
        OUT_STEP,
        MIN_RATIO,
        GAIN,
        SKIN,
        SKY,
        SKIN_HUE_LO,
        SKIN_HUE_HI,
        SKIN_SAT_LO,
        SKIN_SAT_HI,
        SKY_HUE_LO,
        SKY_HUE_HI,
        SKY_BRIGHT
    }

    /** The scalars of [plan] in [F] order, computed on the CPU exactly as [PhotoLook] and [ToneMap] use them. */
    fun uniforms(plan: LookPlan): FloatArray {
        val p = plan.params
        val tone = plan.tone
        val values = mapOf(
            F.WHITE_GAIN to tone.whiteGain,
            F.FLOOR to tone.floor,
            F.FLOOR_TOP to tone.floor + ToneMap.SHADOW_FADE_STOPS,
            F.PIVOT to p.shadowPivot,
            F.STRENGTH to p.shadowStrength,
            F.MAX_LIFT to p.maxShadowLift,
            F.DETAIL to p.detail,
            F.OUT_MIN to ToneMap.OUT_MIN,
            F.OUT_STEP to ToneMap.OUT_STEP,
            F.MIN_RATIO to PhotoLook.MIN_RATIO_LUMA,
            F.GAIN to plan.saturation,
            F.SKIN to p.skinProtection,
            F.SKY to p.skyProtection,
            F.SKIN_HUE_LO to PhotoLook.SKIN_HUE.start,
            F.SKIN_HUE_HI to PhotoLook.SKIN_HUE.endInclusive,
            F.SKIN_SAT_LO to PhotoLook.SKIN_SATURATION.start,
            F.SKIN_SAT_HI to PhotoLook.SKIN_SATURATION.endInclusive,
            F.SKY_HUE_LO to PhotoLook.SKY_HUE.start,
            F.SKY_HUE_HI to PhotoLook.SKY_HUE.endInclusive,
            F.SKY_BRIGHT to PhotoLook.SKY_BRIGHTNESS
        )
        return FloatArray(F.entries.size) { values.getValue(F.entries[it]) }
    }

    private fun f(name: F) = "uF[${name.ordinal}]"

    /** [PhotoLook]'s per-pixel loop (bilinear base, [ToneMap.map], ratio, gamut pull, [ToneMap.finish], protected gain). */
    val FRAGMENT: String = GpuStripPass.HEADER + """
uniform sampler2D uLog;
uniform sampler2D uTone;
uniform sampler2D uFinish;
uniform sampler2D uA;
uniform sampler2D uB;
uniform ivec2 uLowSize;
uniform int uFactor;
uniform float uF[${F.entries.size}];

float luma(vec3 c) { return 0.299 * c.r + 0.587 * c.g + 0.114 * c.b; }
float smooth01(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
float shadowLift(float base) {
    float below = ${f(F.PIVOT)} - base;
    if (below <= 0.0) return 0.0;
    float lift = min(${f(F.STRENGTH)} * below, ${f(F.MAX_LIFT)});
    return lift * smooth01(${f(F.FLOOR)}, ${f(F.FLOOR_TOP)}, base);
}
float mapTone(float l, float base) {
    float detail = ${f(F.DETAIL)} * smooth01(${f(F.FLOOR)}, ${f(F.FLOOR_TOP)}, base);
    float o = l + ${f(F.WHITE_GAIN)} + shadowLift(base) + detail * (l - base);
    return lookup(uTone, clamp((o - ${f(F.OUT_MIN)}) / ${f(F.OUT_STEP)}, 0.0, ${ToneMap.OUT_SIZE}.0), ${ToneMap.OUT_SIZE});
}
float finish(float v) { return lookup(uFinish, clamp(v, 0.0, 1.0) * ${ToneMap.FINISH_SIZE}.0, ${ToneMap.FINISH_SIZE}); }
float protectedGain(vec3 c, float gain) {
    float mx = max(c.r, max(c.g, c.b));
    float range = mx - min(c.r, min(c.g, c.b));
    if (range <= 0.0 || gain <= 1.0) return gain;
    float s = range / mx;
    float skinHue = (c.g - c.b) / range;
    if (c.r > c.g && c.g > c.b && skinHue >= ${f(F.SKIN_HUE_LO)} && skinHue <= ${f(F.SKIN_HUE_HI)} &&
        s >= ${f(F.SKIN_SAT_LO)} && s <= ${f(F.SKIN_SAT_HI)}) return 1.0 + (gain - 1.0) * ${f(F.SKIN)};
    float skyHue = (c.r - c.g) / range;
    if (c.b >= c.r && c.b >= c.g && skyHue >= ${f(F.SKY_HUE_LO)} && skyHue <= ${f(F.SKY_HUE_HI)} &&
        mx > ${f(F.SKY_BRIGHT)}) return 1.0 + (gain - 1.0) * ${f(F.SKY)};
    return gain;
}
// One low plane bilinearly at full-size pixel (x, y): vertical first, then horizontal, as PhotoLook.verticalLerp.
float lowAt(sampler2D p, int x, int y) {
    float fy = clamp((float(y) + 0.5) / float(uFactor) - 0.5, 0.0, float(uLowSize.y - 1));
    int y0 = int(fy);
    int y1 = min(y0 + 1, uLowSize.y - 1);
    float ty = fy - float(y0);
    float fx = clamp((float(x) + 0.5) / float(uFactor) - 0.5, 0.0, float(uLowSize.x - 1));
    int i0 = int(fx);
    int i1 = min(i0 + 1, uLowSize.x - 1);
    float tx = fx - float(i0);
    float a0 = texelFetch(p, ivec2(i0, y0), 0).r;
    float r0 = a0 + (texelFetch(p, ivec2(i0, y1), 0).r - a0) * ty;
    float a1 = texelFetch(p, ivec2(i1, y0), 0).r;
    float r1 = a1 + (texelFetch(p, ivec2(i1, y1), 0).r - a1) * ty;
    return r0 + (r1 - r0) * tx;
}
void main() {
    vec4 t = pixel();
    vec3 c = t.bgr;
    int x = int(gl_FragCoord.x);
    int y = uStripY + int(gl_FragCoord.y);
    float a = lowAt(uA, x, y);
    float b = lowAt(uB, x, y);
    float y1 = luma(c);
    float l = lookup(uLog, clamp(y1, 0.0, 1.0) * ${LogLuma.SIZE}.0, ${LogLuma.SIZE});
    float v = mapTone(l, a * l + b);
    vec3 o = vec3(v);
    if (y1 >= ${f(F.MIN_RATIO)}) {
        o = c * (v / y1);
        float mx = max(o.r, max(o.g, o.b));
        if (mx > 1.0) o = v + (o - v) * ((1.0 - v) / (mx - v));
    }
    o = vec3(finish(o.r), finish(o.g), finish(o.b));
    float yo = luma(o);
    float k = protectedGain(o, ${f(F.GAIN)});
    emit(clamp(yo + k * (o - yo), 0.0, 1.0), 1.0);
}
"""
}
