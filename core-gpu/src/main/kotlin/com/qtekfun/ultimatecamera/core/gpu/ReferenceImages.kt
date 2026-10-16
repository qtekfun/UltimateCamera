package com.qtekfun.ultimatecamera.core.gpu

/** Synthetic test images shared by the CPU reference tests and the on-device shader tests. */
object ReferenceImages {
    const val WIDTH = 256
    const val HEIGHT = 96

    /**
     * Three bands, ARGB_8888 row-major: a grey ramp (banding, tone curve), a hue sweep at full saturation (gamut) and a
     * hue sweep fading to white (skin-tone and pastel region).
     */
    fun gradient(): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val x = i % WIDTH
        val y = i / WIDTH
        val t = x / (WIDTH - 1f)
        val (r, g, b) = when (y * 3 / HEIGHT) {
            0 -> Triple(t, t, t)
            1 -> hue(t)
            else -> hue(t).let { (hr, hg, hb) -> Triple(0.5f + hr * 0.5f, 0.5f + hg * 0.5f, 0.5f + hb * 0.5f) }
        }
        (0xFF shl 24) or (r.to8() shl 16) or (g.to8() shl 8) or b.to8()
    }

    private fun hue(t: Float): Triple<Float, Float, Float> {
        val h = t * 6f
        val f = h - h.toInt()
        return when (h.toInt() % 6) {
            0 -> Triple(1f, f, 0f)
            1 -> Triple(1f - f, 1f, 0f)
            2 -> Triple(0f, 1f, f)
            3 -> Triple(0f, 1f - f, 1f)
            4 -> Triple(f, 0f, 1f)
            else -> Triple(1f, 0f, 1f - f)
        }
    }

    private fun Float.to8(): Int = (coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
