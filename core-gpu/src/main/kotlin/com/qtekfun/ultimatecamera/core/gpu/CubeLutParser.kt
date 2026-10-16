package com.qtekfun.ultimatecamera.core.gpu

/** Thrown for a `.cube` file that cannot be used; [line] is 1-based, or 0 when the problem is not tied to a line. */
class CubeLutFormatException(message: String, val line: Int, cause: Throwable? = null) :
    IllegalArgumentException(if (line > 0) "line $line: $message" else message, cause)

/**
 * Parses `.cube` text (Adobe Cube LUT Specification 1.0, as written by Resolve and the phone makers' Log LUTs).
 * Only 3D LUTs are supported; a 1D LUT (`LUT_1D_SIZE`) is rejected with a clear error.
 */
object CubeLutParser {
    fun parse(text: String): CubeLut {
        val state = State()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isNotEmpty()) state.accept(line, index + 1)
        }
        return state.build()
    }

    private class State {
        var size = 0
        var title: String? = null
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var count = 0

        fun accept(line: String, lineNo: Int) {
            when (val keyword = line.split(WHITESPACE, limit = 2).first()) {
                "TITLE" -> title = line.substringAfter("TITLE").trim().trim('"')
                "LUT_1D_SIZE" -> throw CubeLutFormatException("1D LUTs are not supported", lineNo)
                "LUT_3D_SIZE" -> size3d(line, lineNo)
                "DOMAIN_MIN" -> domainMin = line.floats(lineNo, skip = 1)
                "DOMAIN_MAX" -> domainMax = line.floats(lineNo, skip = 1)
                "LUT_3D_INPUT_RANGE" -> {
                    val range = line.floats(lineNo, skip = 1, expected = 2)
                    domainMin = floatArrayOf(range[0], range[0], range[0])
                    domainMax = floatArrayOf(range[1], range[1], range[1])
                }
                else -> if (keyword.first().isLetter()) Unit else entry(line, lineNo) // Unknown keywords are allowed and ignored.
            }
        }

        private fun size3d(line: String, lineNo: Int) {
            size = line.fields(lineNo, 2)[1].toIntOrNull()
                ?.takeIf { it in CubeLut.MIN_SIZE..CubeLut.MAX_SIZE }
                ?: throw CubeLutFormatException("invalid LUT_3D_SIZE", lineNo)
            data = FloatArray(size * size * size * 3)
        }

        private fun entry(line: String, lineNo: Int) {
            val target = data ?: throw CubeLutFormatException("data before LUT_3D_SIZE", lineNo)
            if (count * 3 >= target.size) throw CubeLutFormatException("more entries than LUT_3D_SIZE³", lineNo)
            line.floats(lineNo, skip = 0).copyInto(target, count * 3)
            count++
        }

        fun build(): CubeLut {
            val values = data ?: throw CubeLutFormatException("missing LUT_3D_SIZE", 0)
            if (count != size * size * size) throw CubeLutFormatException("expected ${size * size * size} entries, found $count", 0)
            return try {
                CubeLut(size, values, title, domainMin, domainMax)
            } catch (e: IllegalArgumentException) {
                throw CubeLutFormatException(e.message ?: "invalid LUT", 0, e)
            }
        }
    }

    private fun String.fields(lineNo: Int, expected: Int): List<String> {
        val parts = split(WHITESPACE)
        if (parts.size != expected) throw CubeLutFormatException("expected $expected fields, found ${parts.size}", lineNo)
        return parts
    }

    private fun String.floats(lineNo: Int, skip: Int, expected: Int = 3): FloatArray {
        val parts = fields(lineNo, skip + expected).drop(skip)
        return FloatArray(expected) { i ->
            parts[i].toFloatOrNull()?.takeIf { it.isFinite() } ?: throw CubeLutFormatException("not a number: ${parts[i]}", lineNo)
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
