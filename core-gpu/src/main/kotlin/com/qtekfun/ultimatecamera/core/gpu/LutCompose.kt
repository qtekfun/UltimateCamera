package com.qtekfun.ultimatecamera.core.gpu

/**
 * Chains two LUTs into one ([second] applied to the output of [first]) so the monitor can show "what is recorded, then
 * the monitor LUT" in a single lookup (REQ-COL-004). Evaluated on the CPU with [LutMath] once, when a choice changes; the
 * per-frame path stays one 3D texture fetch.
 */
object LutCompose {
    /** Largest lattice the result gets: 65 points (≈ 3.3 MB RGB16F) is what the makers ship; more only costs memory. */
    const val MAX_SIZE = 65

    fun compose(first: CubeLut, second: CubeLut, size: Int = maxOf(first.size, second.size).coerceAtMost(MAX_SIZE)): CubeLut {
        val a = FloatArray(3)
        val b = FloatArray(3)
        val title = listOfNotNull(first.title, second.title).joinToString(" + ").ifEmpty { null }
        val range = FloatArray(3) { first.domainMax[it] - first.domainMin[it] }
        return CubeLut.generate(size, title) { r, g, bl ->
            // The result's lattice covers the first LUT's input domain.
            LutMath.apply(
                first,
                first.domainMin[0] + r * range[0],
                first.domainMin[1] + g * range[1],
                first.domainMin[2] + bl * range[2],
                a
            )
            LutMath.apply(second, a[0], a[1], a[2], b)
            Triple(b[0], b[1], b[2])
        }.withDomain(first.domainMin, first.domainMax)
    }

    private fun CubeLut.withDomain(min: FloatArray, max: FloatArray) = CubeLut(size, data, title, min.copyOf(), max.copyOf())
}
