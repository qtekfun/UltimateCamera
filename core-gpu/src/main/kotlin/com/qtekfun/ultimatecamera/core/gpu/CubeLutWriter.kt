package com.qtekfun.ultimatecamera.core.gpu

import java.util.Locale

/** Writes a [CubeLut] as `.cube` text that [CubeLutParser] (and Resolve) read back. */
object CubeLutWriter {
    fun write(lut: CubeLut): String = buildString {
        lut.title?.let { append("TITLE \"").append(it).append("\"\n") }
        append("LUT_3D_SIZE ").append(lut.size).append('\n')
        append("DOMAIN_MIN ").append(lut.domainMin.joinToString(" ") { it.format() }).append('\n')
        append("DOMAIN_MAX ").append(lut.domainMax.joinToString(" ") { it.format() }).append('\n')
        for (i in 0 until lut.data.size / 3) {
            append(lut.data[i * 3].format()).append(' ')
            append(lut.data[i * 3 + 1].format()).append(' ')
            append(lut.data[i * 3 + 2].format()).append('\n')
        }
    }

    private fun Float.format(): String = String.format(Locale.ROOT, "%.6f", this)
}
