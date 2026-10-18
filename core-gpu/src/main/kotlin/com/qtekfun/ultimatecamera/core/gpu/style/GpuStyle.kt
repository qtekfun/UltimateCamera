package com.qtekfun.ultimatecamera.core.gpu.style

import android.opengl.GLES30
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.photo.GpuStripPass

/**
 * A photo style on the GPU (D90; [StyleEngine.applyStyle] took 2.8 s for 12 MP on the Pixel 8's CPU): the style's
 * 33³ LUT ([StyleEngine.lutFor]) as an RGB32F 3D texture, applied by [FRAGMENT] with the same trilinear arithmetic as
 * [com.qtekfun.ultimatecamera.core.gpu.LutMath.apply] (fetched lattice points, not the texture unit's filtering, whose
 * weights are only 8-bit on many GPUs) and the same 8-bit rounding; alpha is kept. Within one level of the CPU.
 *
 * Create, use and close on one thread.
 */
class GpuStyle : AutoCloseable {
    private val pass = GpuStripPass(FRAGMENT)
    private var loaded: CubeLut? = null

    /** Applies [lut] over the ARGB image [read] gives (`rows` rows from `y`), handing the result to [write]. */
    fun apply(lut: CubeLut, width: Int, height: Int, read: (IntArray, Int, Int) -> Unit, write: (IntArray, Int, Int) -> Unit) {
        if (loaded !== lut) {
            pass.lut3d(1, "uLut", lut.size, lut.data)
            loaded = lut
        }
        GLES30.glUniform1i(pass.uniform("uSize"), lut.size)
        GLES30.glUniform3f(pass.uniform("uMin"), lut.domainMin[0], lut.domainMin[1], lut.domainMin[2])
        GLES30.glUniform3f(pass.uniform("uMax"), lut.domainMax[0], lut.domainMax[1], lut.domainMax[2])
        pass.run(width, height, read, write)
    }

    override fun close() = pass.close()

    companion object {
        val FRAGMENT: String = GpuStripPass.HEADER + """
precision highp sampler3D;
uniform sampler3D uLut;
uniform int uSize;
uniform vec3 uMin;
uniform vec3 uMax;

vec3 at(ivec3 i) { return texelFetch(uLut, i, 0).rgb; }

void main() {
    vec4 t = pixel();
    float m = float(uSize - 1);
    vec3 p = clamp((t.bgr - uMin) / (uMax - uMin), 0.0, 1.0) * m;
    ivec3 i = min(ivec3(p), ivec3(uSize - 2));
    vec3 f = p - vec3(i);
    vec3 c000 = at(i);
    vec3 c100 = at(i + ivec3(1, 0, 0));
    vec3 c010 = at(i + ivec3(0, 1, 0));
    vec3 c110 = at(i + ivec3(1, 1, 0));
    vec3 c001 = at(i + ivec3(0, 0, 1));
    vec3 c101 = at(i + ivec3(1, 0, 1));
    vec3 c011 = at(i + ivec3(0, 1, 1));
    vec3 c111 = at(i + ivec3(1, 1, 1));
    vec3 c00 = c000 + (c100 - c000) * f.x;
    vec3 c10 = c010 + (c110 - c010) * f.x;
    vec3 c01 = c001 + (c101 - c001) * f.x;
    vec3 c11 = c011 + (c111 - c011) * f.x;
    vec3 c0 = c00 + (c10 - c00) * f.y;
    vec3 c1 = c01 + (c11 - c01) * f.y;
    emit(c0 + (c1 - c0) * f.z, t.a);
}
"""
    }
}
