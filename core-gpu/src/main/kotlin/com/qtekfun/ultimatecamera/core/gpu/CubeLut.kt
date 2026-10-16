package com.qtekfun.ultimatecamera.core.gpu

/**
 * A 3D colour lookup table as defined by the Adobe/Resolve `.cube` format.
 *
 * [data] holds `size³` RGB triplets with red changing fastest, then green, then blue (the file order), so the entry for
 * lattice point (r, g, b) starts at `3 * (r + g * size + b * size * size)`. Inputs are expected in [domainMin, domainMax].
 */
class CubeLut(
    val size: Int,
    val data: FloatArray,
    val title: String? = null,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f)
) {
    init {
        require(size in MIN_SIZE..MAX_SIZE) { "LUT_3D_SIZE must be in $MIN_SIZE..$MAX_SIZE, was $size" }
        require(data.size == size * size * size * 3) { "expected ${size * size * size} entries, got ${data.size / 3}" }
        require(domainMin.size == 3 && domainMax.size == 3) { "domain must have three components" }
        require((0..2).all { domainMax[it] > domainMin[it] }) { "DOMAIN_MAX must be greater than DOMAIN_MIN" }
    }

    fun index(r: Int, g: Int, b: Int): Int = 3 * (r + g * size + b * size * size)

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 256

        /** A LUT that maps every colour onto itself. */
        fun identity(size: Int): CubeLut = generate(size, "Identity") { r, g, b -> Triple(r, g, b) }

        /** Builds a LUT by evaluating [transform] at every lattice point (inputs in 0..1). */
        fun generate(size: Int, title: String? = null, transform: (Float, Float, Float) -> Triple<Float, Float, Float>): CubeLut {
            val data = FloatArray(size * size * size * 3)
            val step = 1f / (size - 1)
            var i = 0
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        val (outR, outG, outB) = transform(r * step, g * step, b * step)
                        data[i++] = outR
                        data[i++] = outG
                        data[i++] = outB
                    }
                }
            }
            return CubeLut(size, data, title)
        }
    }
}
