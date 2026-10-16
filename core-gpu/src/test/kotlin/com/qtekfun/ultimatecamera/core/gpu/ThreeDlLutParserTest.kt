package com.qtekfun.ultimatecamera.core.gpu

import java.io.StringReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeDlLutParserTest {
    /** Size 2, 10-bit in, 12-bit out. Entry order: blue fastest. Output channel r = 4095 · b_in (a channel swap). */
    private val nukeStyle = """
        # Nuke-style, no header
        0 1023
        0 0 0
        4095 0 0
        0 4095 0
        4095 4095 0
        0 0 4095
        4095 0 4095
        0 4095 4095
        4095 4095 4095
    """.trimIndent()

    @Test
    fun readsBlueFastestIntoTheCubeLayout() {
        val lut = ThreeDlLutParser.parse(nukeStyle)
        assertEquals(2, lut.size)
        // File entry 1 is lattice point (r=0, g=0, b=1) and holds output (1, 0, 0).
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), lut.at(0, 0, 1), 1e-6f)
        // File entry 4 is (r=1, g=0, b=0) and holds output (0, 0, 1).
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), lut.at(1, 0, 0), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), lut.at(0, 1, 0), 1e-6f)
        // Same LUT written as .cube (red fastest) must be the same table.
        val cube = CubeLut.generate(2) { r, g, b -> Triple(b, g, r) }
        assertArrayEquals(cube.data, lut.data, 1e-6f)
    }

    @Test
    fun writerAndParserAgreeWithAnAsymmetricCubeLut() {
        val cube = CubeLut.generate(17, "asym") { r, g, b -> Triple(r * 0.9f + b * 0.1f, g * g, 1f - r * 0.5f) }
        val back = ThreeDlLutParser.parse(ThreeDlLutWriter.write(cube, inputBits = 10, outputBits = 12))
        assertEquals(17, back.size)
        assertArrayEquals(cube.data, back.data, 0.5f / 4095f + 1e-6f)
        // And the LUT does the same thing to colours as the original.
        val a = FloatArray(3)
        val b = FloatArray(3)
        LutMath.apply(cube, 0.2f, 0.7f, 0.9f, a)
        LutMath.apply(back, 0.2f, 0.7f, 0.9f, b)
        assertArrayEquals(a, b, 1e-3f)
    }

    @Test
    fun lustreHeaderSetsMeshSizeAndOutputDepth() {
        // Mesh 1 16 → 3 points, 16-bit output; values never reach 65535, so only the header gives the right depth.
        val text = buildString {
            append("3DMESH\nMesh 1 16\nLUT8\ngamma 1.0\n0 511 1023\n")
            for (r in 0..2) for (g in 0..2) for (b in 0..2) append("${r * 16000} ${g * 16000} ${b * 16000}\n")
        }
        val lut = ThreeDlLutParser.parse(text)
        assertEquals(3, lut.size)
        assertArrayEquals(floatArrayOf(32000f / 65535f, 0f, 16000f / 65535f), lut.at(2, 0, 1), 1e-6f)
    }

    @Test
    fun infersTenBitOutputAndAcceptsTheUsualLastMeshStep() {
        val text = buildString {
            append((0 until 17).joinToString(" ") { if (it == 16) "1023" else (it * 64).toString() }).append('\n')
            repeat(17 * 17 * 17) { append("1023 512 0\n") }
        }
        val lut = ThreeDlLutParser.parse(text)
        assertEquals(17, lut.size)
        assertArrayEquals(floatArrayOf(1f, 512f / 1023f, 0f), lut.at(5, 6, 7), 1e-6f)
        assertEquals(1f, lut.domainMax[0], 1e-6f)
    }

    @Test
    fun floatVariantIsTakenAsNormalised() {
        val text = "0 1023\n" + (0 until 8).joinToString("\n") { "0.25 0.5 1.0" }
        val lut = ThreeDlLutParser.parse(text)
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 1f), lut.at(1, 1, 1), 1e-6f)
        assertNull(lut.title)
    }

    @Test
    fun streamsFromAReaderWithWindowsLineEndings() {
        val lut = ThreeDlLutParser.parse(StringReader(nukeStyle.replace("\n", "\r\n")), title = "from reader")
        assertEquals("from reader", lut.title)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), lut.at(0, 0, 1), 1e-6f)
    }

    @Test
    fun rejectsMalformedFilesWithTheLine() {
        fun fails(text: String, line: Int? = null, contains: String? = null) {
            val e = assertThrows(CubeLutFormatException::class.java) { ThreeDlLutParser.parse(text) }
            line?.let { assertEquals(e.message, it, e.line) }
            contains?.let { assertTrue(e.message, e.message!!.contains(it)) }
        }
        fails(nukeStyle.substringBeforeLast("4095 4095 4095"), contains = "expected 8 entries")
        fails(nukeStyle + "\n1 2 3", line = 11, contains = "more entries")
        fails(nukeStyle.replace("4095 0 4095", "4095 x 4095"), line = 8)
        fails(nukeStyle.replace("4095 0 4095", "4095 -1 4095"), line = 8)
        fails(nukeStyle.replace("0 4095 0", "0 4095"), line = 5, contains = "expected 3 values")
        fails("0 100 1023\n", line = 1, contains = "non-uniform")
        fails("0 1023 512\n", line = 1, contains = "increasing")
        fails("Mesh 2 12\n0 1023\n", line = 2, contains = "Mesh header says 5")
        fails("Mesh 2 99\n", line = 1, contains = "output bits")
        fails("# only a comment\n", contains = "missing input mesh")
        fails(nukeStyle.replace("4095 4095 4095", "4095 4095 999999"), contains = "output maximum")
    }

    @Test
    fun enforcesSizeAndLineLimits() {
        val limits = LutParseLimits(maxSize = 17)
        val big = (0 until 33).joinToString(" ") { (it * 32).coerceAtMost(1023).toString() }
        val e = assertThrows(CubeLutFormatException::class.java) { ThreeDlLutParser.parse(big, limits) }
        assertTrue(e.message, e.message!!.contains("larger than the supported 17"))
        val longLine = "0 1023 " + " ".repeat(LutParseLimits.DEFAULT_MAX_LINE_LENGTH)
        val tooLong = assertThrows(CubeLutFormatException::class.java) { ThreeDlLutParser.parse(longLine) }
        assertEquals(1, tooLong.line)
    }

    @Test
    fun formatIsDetectedFromTheFileName() {
        assertEquals(LutFormat.THREE_DL, LutFormat.fromFileName("Look.3DL"))
        assertEquals(LutFormat.CUBE, LutFormat.fromFileName("O-Log-Rec.709 Gamma2.4_v1.6.cube"))
        assertNull(LutFormat.fromFileName("notes.txt"))
        assertNull(LutFormat.fromFileName("cube"))
        val viaFiles = LutFiles.parse(LutFormat.THREE_DL, StringReader(nukeStyle))
        assertEquals(2, viaFiles.size)
    }

    private fun CubeLut.at(r: Int, g: Int, b: Int): FloatArray = data.copyOfRange(index(r, g, b), index(r, g, b) + 3)
}
