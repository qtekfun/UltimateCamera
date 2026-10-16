package com.qtekfun.ultimatecamera.core.gpu

import java.io.Reader
import java.io.StringReader

/**
 * Thrown for a LUT file (`.cube` or `.3dl`) that cannot be used; [line] is 1-based, or 0 when the problem is not tied to
 * a line.
 */
class CubeLutFormatException(message: String, val line: Int, cause: Throwable? = null) :
    IllegalArgumentException(if (line > 0) "line $line: $message" else message, cause)

/**
 * Parses `.cube` text (Adobe Cube LUT Specification 1.0, as written by Resolve and the phone makers' Log LUTs).
 * Only 3D LUTs are supported; a 1D LUT (`LUT_1D_SIZE`) is rejected with a clear error. The reader is consumed as a
 * stream: memory is the lattice (bounded by [LutParseLimits.maxSize]) plus one line.
 */
object CubeLutParser {
    fun parse(text: String, limits: LutParseLimits = LutParseLimits()): CubeLut = parse(StringReader(text), limits)

    fun parse(reader: Reader, limits: LutParseLimits = LutParseLimits()): CubeLut {
        val state = State(limits)
        forEachLutLine(reader, limits.maxLineLength) { tokens, text, lineNo -> state.accept(tokens, text, lineNo) }
        return state.build()
    }

    private class State(private val limits: LutParseLimits) {
        var size = 0
        var title: String? = null
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var count = 0

        fun accept(tokens: List<String>, text: String, lineNo: Int) {
            val keyword = tokens.first()
            when (keyword) {
                "TITLE" -> title = parseTitle(text.substringAfter("TITLE"))
                "LUT_1D_SIZE" -> throw CubeLutFormatException("1D LUTs are not supported", lineNo)
                "LUT_3D_SIZE" -> size3d(tokens, lineNo)
                "DOMAIN_MIN" -> domainMin = tokens.floats(lineNo, skip = 1)
                "DOMAIN_MAX" -> domainMax = tokens.floats(lineNo, skip = 1)
                "LUT_3D_INPUT_RANGE" -> {
                    val range = tokens.floats(lineNo, skip = 1, expected = 2)
                    domainMin = floatArrayOf(range[0], range[0], range[0])
                    domainMax = floatArrayOf(range[1], range[1], range[1])
                }
                else -> if (keyword.first().isLetter()) Unit else entry(tokens, lineNo) // Unknown keywords are allowed and ignored.
            }
        }

        private fun size3d(tokens: List<String>, lineNo: Int) {
            if (data != null) throw CubeLutFormatException("LUT_3D_SIZE given twice", lineNo)
            tokens.expect(2, lineNo)
            size = tokens[1].toIntOrNull()
                ?.takeIf { it in CubeLut.MIN_SIZE..CubeLut.MAX_SIZE }
                ?: throw CubeLutFormatException("invalid LUT_3D_SIZE", lineNo)
            if (size > limits.maxSize) throw CubeLutFormatException("LUT_3D_SIZE $size is larger than the supported ${limits.maxSize}", lineNo)
            data = FloatArray(size * size * size * 3)
        }

        private fun entry(tokens: List<String>, lineNo: Int) {
            val target = data ?: throw CubeLutFormatException("data before LUT_3D_SIZE", lineNo)
            if (count * 3 >= target.size) throw CubeLutFormatException("more entries than LUT_3D_SIZE³", lineNo)
            tokens.expect(3, lineNo)
            for (c in 0..2) target[count * 3 + c] = tokens[c].finiteFloat(lineNo)
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

    /** `TITLE "name"`; text after the closing quote (OPPO writes `"…" BY OPPO …`) is not part of the title. */
    private fun parseTitle(rest: String): String {
        val trimmed = rest.trim()
        if (!trimmed.startsWith('"')) return trimmed
        val close = trimmed.indexOf('"', startIndex = 1)
        return if (close < 0) trimmed.substring(1) else trimmed.substring(1, close)
    }

    private fun List<String>.expect(expected: Int, lineNo: Int) {
        if (size != expected) throw CubeLutFormatException("expected $expected fields, found $size", lineNo)
    }

    private fun List<String>.floats(lineNo: Int, skip: Int, expected: Int = 3): FloatArray {
        expect(skip + expected, lineNo)
        return FloatArray(expected) { i -> this[skip + i].finiteFloat(lineNo) }
    }

    private fun String.finiteFloat(lineNo: Int): Float =
        toFloatOrNull()?.takeIf { it.isFinite() } ?: throw CubeLutFormatException("not a number: $this", lineNo)
}
