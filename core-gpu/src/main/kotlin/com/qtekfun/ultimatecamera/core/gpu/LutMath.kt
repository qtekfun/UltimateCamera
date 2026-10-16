package com.qtekfun.ultimatecamera.core.gpu

/**
 * CPU reference for applying a [CubeLut]. It is the specification the GPU shader is tested against: trilinear
 * interpolation between lattice points, which is exactly what a linearly filtered 3D texture computes when it is sampled
 * at `coord * (size - 1) / size + 0.5 / size` (see [LutShaders]).
 */
object LutMath {
    /** Writes the transformed colour into [out] (three floats). Inputs outside the domain are clamped. */
    fun apply(lut: CubeLut, r: Float, g: Float, b: Float, out: FloatArray) {
        val n = lut.size
        val max = (n - 1).toFloat()
        val x = (normalise(r, lut, 0) * max)
        val y = (normalise(g, lut, 1) * max)
        val z = (normalise(b, lut, 2) * max)
        val x0 = x.toInt().coerceAtMost(n - 2)
        val y0 = y.toInt().coerceAtMost(n - 2)
        val z0 = z.toInt().coerceAtMost(n - 2)
        val fx = x - x0
        val fy = y - y0
        val fz = z - z0
        val d = lut.data
        for (c in 0..2) {
            val c000 = d[lut.index(x0, y0, z0) + c]
            val c100 = d[lut.index(x0 + 1, y0, z0) + c]
            val c010 = d[lut.index(x0, y0 + 1, z0) + c]
            val c110 = d[lut.index(x0 + 1, y0 + 1, z0) + c]
            val c001 = d[lut.index(x0, y0, z0 + 1) + c]
            val c101 = d[lut.index(x0 + 1, y0, z0 + 1) + c]
            val c011 = d[lut.index(x0, y0 + 1, z0 + 1) + c]
            val c111 = d[lut.index(x0 + 1, y0 + 1, z0 + 1) + c]
            val c00 = c000 + (c100 - c000) * fx
            val c10 = c010 + (c110 - c010) * fx
            val c01 = c001 + (c101 - c001) * fx
            val c11 = c011 + (c111 - c011) * fx
            val c0 = c00 + (c10 - c00) * fy
            val c1 = c01 + (c11 - c01) * fy
            out[c] = c0 + (c1 - c0) * fz
        }
    }

    /** Applies [lut] to an ARGB_8888 image (alpha kept), rounding to 8 bits like an RGBA8 framebuffer does. */
    fun applyToArgb(lut: CubeLut, pixels: IntArray): IntArray {
        val out = FloatArray(3)
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            apply(lut, ((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f, out)
            (p and 0xFF000000.toInt()) or (out[0].to8() shl 16) or (out[1].to8() shl 8) or out[2].to8()
        }
    }

    private fun normalise(v: Float, lut: CubeLut, channel: Int): Float =
        ((v - lut.domainMin[channel]) / (lut.domainMax[channel] - lut.domainMin[channel])).coerceIn(0f, 1f)

    private fun Float.to8(): Int = (coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
