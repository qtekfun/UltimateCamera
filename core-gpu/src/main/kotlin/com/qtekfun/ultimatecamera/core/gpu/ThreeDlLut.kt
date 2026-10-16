package com.qtekfun.ultimatecamera.core.gpu

import java.io.Reader
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Parses Autodesk `.3dl` 3D LUTs (Lustre, Flame, Nuke and Resolve exports) into the same [CubeLut] representation the
 * `.cube` parser produces.
 *
 * The variants differ only in a few details, all handled here:
 * - Optional header lines: Lustre's `3DMESH` and `Mesh <inBits> <outBits>` (mesh size `2^inBits + 1`, output depth
 *   `outBits`), `LUT8`, `gamma …` and other keywords (ignored). `#` starts a comment.
 * - The first numeric line is the input mesh: the integer input value of every lattice point along one axis, e.g.
 *   `0 64 128 … 1023`. Its count is the lattice size; its last value gives the input bit depth. The mesh must be
 *   uniform (within 1.5 codes, so `… 960 1023` is accepted); a non-uniform mesh is rejected rather than silently
 *   misread.
 * - Then `size³` lines of three output values. **Blue changes fastest, then green, then red** (the opposite of
 *   `.cube`): file entry k is lattice point `r = k / size², g = (k / size) % size, b = k % size`. The parser stores it
 *   at [CubeLut.index] so both formats produce the same table.
 * - Output values are integers of a bit depth taken from the `Mesh` header or, without one, the smallest even depth
 *   from 8 to 16 bits that holds the largest value (as OpenColorIO does; 1 % tolerance so `1024` still reads as 10-bit).
 *   Flame float variants (values with a decimal point) are taken as already normalised.
 */
object ThreeDlLutParser {
    fun parse(text: String, limits: LutParseLimits = LutParseLimits()): CubeLut = parse(StringReader(text), limits)

    fun parse(reader: Reader, limits: LutParseLimits = LutParseLimits(), title: String? = null): CubeLut {
        val state = State(limits)
        forEachLutLine(reader, limits.maxLineLength) { tokens, _, lineNo -> state.accept(tokens, lineNo) }
        return state.build(title)
    }

    private class State(private val limits: LutParseLimits) {
        var meshSize = 0
        var meshOutBits = 0
        var mesh: FloatArray? = null
        var meshLine = 0
        var size = 0
        var data: FloatArray? = null
        var count = 0
        var maxValue = 0f
        var floatValues = false

        fun accept(tokens: List<String>, lineNo: Int) {
            val first = tokens.first()
            if (first.toFloatOrNull() == null) {
                keyword(tokens, lineNo)
                return
            }
            if (mesh == null) inputMesh(tokens, lineNo) else entry(tokens, lineNo)
        }

        private fun keyword(tokens: List<String>, lineNo: Int) {
            if (!tokens.first().equals("Mesh", ignoreCase = true)) return // 3DMESH, LUT8, gamma … carry nothing we use.
            if (mesh != null) throw CubeLutFormatException("Mesh header after the input mesh", lineNo)
            if (tokens.size != 3) throw CubeLutFormatException("expected 'Mesh <inBits> <outBits>'", lineNo)
            val inBits = tokens[1].toIntOrNull()?.takeIf { it in 1..MAX_MESH_BITS }
                ?: throw CubeLutFormatException("invalid mesh input bits: ${tokens[1]}", lineNo)
            meshOutBits = tokens[2].toIntOrNull()?.takeIf { it in MIN_DEPTH..MAX_DEPTH }
                ?: throw CubeLutFormatException("invalid mesh output bits: ${tokens[2]}", lineNo)
            meshSize = (1 shl inBits) + 1
        }

        private fun inputMesh(tokens: List<String>, lineNo: Int) {
            val n = tokens.size
            if (n < CubeLut.MIN_SIZE) throw CubeLutFormatException("input mesh needs at least ${CubeLut.MIN_SIZE} values", lineNo)
            if (n > limits.maxSize) throw CubeLutFormatException("mesh size $n is larger than the supported ${limits.maxSize}", lineNo)
            if (meshSize != 0 && meshSize != n) throw CubeLutFormatException("Mesh header says $meshSize points, input mesh has $n", lineNo)
            val values = FloatArray(n) { tokens[it].nonNegative(lineNo) }
            checkUniform(values, lineNo)
            mesh = values
            meshLine = lineNo
            size = n
            data = FloatArray(n * n * n * 3)
        }

        private fun checkUniform(values: FloatArray, lineNo: Int) {
            val n = values.size
            val problem = when {
                (1 until n).any { values[it] <= values[it - 1] } -> "input mesh must be increasing"
                values[0] != 0f -> "input mesh must start at 0"
                else -> (0 until n).firstOrNull { abs(values[it] - it * values[n - 1] / (n - 1)) > MESH_TOLERANCE }
                    ?.let { "non-uniform input mesh is not supported (value ${it + 1} is ${values[it]})" }
            }
            if (problem != null) throw CubeLutFormatException(problem, lineNo)
        }

        private fun entry(tokens: List<String>, lineNo: Int) {
            val target = data ?: error("mesh not read")
            if (tokens.size != 3) throw CubeLutFormatException("expected 3 values, found ${tokens.size}", lineNo)
            if (count >= size * size * size) throw CubeLutFormatException("more entries than $size³", lineNo)
            // File order is blue fastest; CubeLut is red fastest.
            val r = count / (size * size)
            val g = (count / size) % size
            val b = count % size
            val at = 3 * (r + g * size + b * size * size)
            for (c in 0..2) {
                val token = tokens[c]
                if (token.contains('.') || token.contains('e', ignoreCase = true)) floatValues = true
                val v = token.nonNegative(lineNo)
                if (v > maxValue) maxValue = v
                target[at + c] = v
            }
            count++
        }

        fun build(title: String?): CubeLut {
            val values = data ?: throw CubeLutFormatException("missing input mesh line", 0)
            val expected = size * size * size
            if (count != expected) throw CubeLutFormatException("expected $expected entries after the mesh, found $count", 0)
            val inputMax = mesh!!.last()
            val inputScale = likelyMaxCode(inputMax)
                ?: throw CubeLutFormatException("input mesh maximum $inputMax is not an integer range of 8 to 16 bits", meshLine)
            val outputScale = when {
                floatValues -> 1f
                meshOutBits != 0 -> ((1 shl meshOutBits) - 1).toFloat()
                else -> likelyMaxCode(maxValue)
                    ?: throw CubeLutFormatException("output maximum $maxValue is not an integer range of 8 to 16 bits", 0)
            }
            for (i in values.indices) values[i] /= outputScale
            val domainMax = inputMax / inputScale
            return CubeLut(size, values, title, domainMax = floatArrayOf(domainMax, domainMax, domainMax))
        }
    }

    /** `2^bits − 1` for the smallest even depth in 8..16 bits that holds [value] (1 % tolerance), or null. */
    internal fun likelyMaxCode(value: Float): Float? {
        for (bits in MIN_DEPTH..MAX_DEPTH step 2) {
            val max = ((1 shl bits) - 1).toFloat()
            if (value <= max * DEPTH_TOLERANCE) return max
        }
        return null
    }

    private fun String.nonNegative(lineNo: Int): Float =
        toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: throw CubeLutFormatException("not a non-negative number: $this", lineNo)

    private const val MAX_MESH_BITS = 7
    private const val MIN_DEPTH = 8
    private const val MAX_DEPTH = 16
    private const val MESH_TOLERANCE = 1.5f
    private const val DEPTH_TOLERANCE = 1.01f
}

/** Writes a [CubeLut] as an integer `.3dl` (Nuke/Lustre layout, blue fastest) that [ThreeDlLutParser] reads back. */
object ThreeDlLutWriter {
    /** [inputBits] sets the input mesh range (10 → 0..1023), [outputBits] the integer output range (12 → 0..4095). */
    fun write(lut: CubeLut, out: Appendable, inputBits: Int = 10, outputBits: Int = 12) {
        require(inputBits in 8..16 && outputBits in 8..16) { "bit depths must be in 8..16" }
        val n = lut.size
        val inMax = (1 shl inputBits) - 1
        val outMax = (1 shl outputBits) - 1
        lut.title?.let { out.append("# ").append(it).append('\n') }
        for (i in 0 until n) {
            if (i > 0) out.append(' ')
            out.append((i * inMax.toFloat() / (n - 1)).roundToInt().toString())
        }
        out.append('\n')
        // File order: blue fastest, then green, then red.
        for (k in 0 until n * n * n) {
            val at = lut.index(r = k / (n * n), g = (k / n) % n, b = k % n)
            for (c in 0..2) {
                if (c > 0) out.append(' ')
                out.append((lut.data[at + c].coerceIn(0f, 1f) * outMax).roundToInt().toString())
            }
            out.append('\n')
        }
    }

    fun write(lut: CubeLut, inputBits: Int = 10, outputBits: Int = 12): String = buildString { write(lut, this, inputBits, outputBits) }
}
