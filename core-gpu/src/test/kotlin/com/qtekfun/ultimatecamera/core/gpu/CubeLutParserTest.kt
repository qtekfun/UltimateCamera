package com.qtekfun.ultimatecamera.core.gpu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutParserTest {
    private val minimal = """
        # Comment line
        TITLE "Test LUT"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0 0 0
        DOMAIN_MAX 1 1 1
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1   # trailing comment
        0 1 1
        1 1 1
    """.trimIndent()

    @Test
    fun parsesHeaderAndDataInFileOrder() {
        val lut = CubeLutParser.parse(minimal)
        assertEquals(2, lut.size)
        assertEquals("Test LUT", lut.title)
        // Red changes fastest: entry 1 is (r=1, g=0, b=0).
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), lut.data.copyOfRange(lut.index(1, 0, 0), lut.index(1, 0, 0) + 3), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f), lut.data.copyOfRange(lut.index(0, 1, 1), lut.index(0, 1, 1) + 3), 0f)
    }

    @Test
    fun acceptsWindowsLineEndingsTabsAndUnknownKeywords() {
        val text = minimal.replace("\n", "\r\n").replace("LUT_3D_SIZE 2", "LUT_3D_SIZE\t2\r\nLUT_IN_VIDEO_RANGE")
        assertEquals(2, CubeLutParser.parse(text).size)
    }

    @Test
    fun readsInputRangeAsDomain() {
        val lut = CubeLutParser.parse(minimal.replace("DOMAIN_MIN 0 0 0\nDOMAIN_MAX 1 1 1", "LUT_3D_INPUT_RANGE -0.1 1.2"))
        assertArrayEquals(floatArrayOf(-0.1f, -0.1f, -0.1f), lut.domainMin, 1e-6f)
        assertArrayEquals(floatArrayOf(1.2f, 1.2f, 1.2f), lut.domainMax, 1e-6f)
    }

    @Test
    fun rejectsBrokenFilesWithTheLine() {
        val short = assertThrows(CubeLutFormatException::class.java) { CubeLutParser.parse(minimal.substringBeforeLast("1 1 1")) }
        assertTrue(short.message, short.message!!.contains("expected 8 entries"))
        val nan = assertThrows(CubeLutFormatException::class.java) { CubeLutParser.parse(minimal.replace("1 0 1", "1 x 1")) }
        assertEquals(11, nan.line)
        assertThrows(CubeLutFormatException::class.java) { CubeLutParser.parse("LUT_1D_SIZE 1024\n0 0 0") }
        assertThrows(CubeLutFormatException::class.java) { CubeLutParser.parse("0 0 0\nLUT_3D_SIZE 2") }
        assertThrows(CubeLutFormatException::class.java) { CubeLutParser.parse(minimal + "\n0 0 0") }
        assertThrows(CubeLutFormatException::class.java) { CubeLutParser.parse(minimal.replace("DOMAIN_MAX 1 1 1", "DOMAIN_MAX 0 1 1")) }
    }

    @Test
    fun titleStopsAtTheClosingQuote() {
        // OPPO's O-Log LUTs write `TITLE "…" BY OPPO …`.
        val lut = CubeLutParser.parse(minimal.replace("TITLE \"Test LUT\"", "TITLE \"O-Log-Rec.709 Gamma2.4_v1.6\" BY OPPO Thom"))
        assertEquals("O-Log-Rec.709 Gamma2.4_v1.6", lut.title)
    }

    @Test
    fun streamsFromAReaderAndEnforcesLimits() {
        val lut = CubeLutParser.parse(java.io.StringReader(minimal.replace("\n", "\r")))
        assertEquals(2, lut.size)
        val tooBig = assertThrows(CubeLutFormatException::class.java) { CubeLutParser.parse("LUT_3D_SIZE 200\n") }
        assertEquals(1, tooBig.line)
        assertTrue(tooBig.message, tooBig.message!!.contains("larger than the supported 129"))
        val longLine = assertThrows(CubeLutFormatException::class.java) {
            CubeLutParser.parse(minimal.replace("1 0 0", "1 0 0" + " ".repeat(5000)))
        }
        assertEquals(7, longLine.line)
        assertThrows(CubeLutFormatException::class.java) { CubeLutParser.parse(minimal.replace("LUT_3D_SIZE 2", "LUT_3D_SIZE 2\nLUT_3D_SIZE 2")) }
    }

    @Test
    fun writerOutputParsesBackToTheSameLut() {
        val lut = SpikeLuts.creative(17)
        val back = CubeLutParser.parse(CubeLutWriter.write(lut))
        assertEquals(lut.size, back.size)
        assertEquals(lut.title, back.title)
        assertArrayEquals(lut.data, back.data, 1e-6f)
    }
}
