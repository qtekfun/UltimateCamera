package com.qtekfun.ultimatecamera.core.gpu

import com.qtekfun.ultimatecamera.core.gpu.StampChannel.toFloatArray
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trip and robustness of [FrameStamp] through [StampChannel], the simulated proxy path (1280×720 source). Each
 * robustness test asserts that every row decodes to the right counter; the null rates of the heavier corruptions are
 * in [FrameStampFalsePositiveTest].
 */
class FrameStampTest {
    private val random = Random(20261016)

    @Test
    fun layoutHasMarkersMarginsAndFortyEightCells() {
        val cells = FrameStamp.encode(0x1234)
        assertEquals(FrameStamp.CELLS, cells.size)
        val pattern = cells.joinToString("") { if (it) "1" else "0" }
        assertEquals("0000" + "1101", pattern.substring(0, 8))
        assertEquals("1011" + "0000", pattern.substring(40, 48))
        assertEquals("0001001000110100", pattern.substring(8, 24))
        assertEquals(FrameStamp.crc16(0x1234).toString(2).padStart(16, '0'), pattern.substring(24, 40))
    }

    @Test
    fun crcMatchesReference() {
        // Computed independently (Python, bitwise, poly 0x51E9, init 0xFFFF).
        assertEquals(0x4307, FrameStamp.crc16(0x1234))
        assertEquals(0x6349, FrameStamp.crc16(0))
    }

    @Test
    fun everyErrorOfUpToSixCellsIsDetected() {
        // The code is linear, so its minimum distance is the lowest weight of a non-zero codeword (init removed).
        val init = FrameStamp.crc16(0)
        var minimum = Int.MAX_VALUE
        for (value in 1..FrameStamp.COUNTER_MASK) {
            minimum = minOf(minimum, Integer.bitCount(value) + Integer.bitCount(FrameStamp.crc16(value) xor init))
        }
        assertEquals(7, minimum)
    }

    @Test
    fun renderRowMatchesCells() {
        val width = 1280
        val cells = FrameStamp.encode(0xBEEF)
        val row = FrameStamp.renderRow(0xBEEF, width)
        for (x in 0 until width) assertEquals(if (cells[(2 * x + 1) * FrameStamp.CELLS / (2 * width)]) 255 else 0, row[x])
        assertArrayEquals(row, FrameStamp.renderRow(0xBEEF + 0x10000, width))
    }

    @Test
    fun geometryAt720pAnd360p() {
        assertEquals(29, FrameStamp.stripHeight(720))
        assertEquals(14, FrameStamp.sampleRow(720))
        assertEquals(14, FrameStamp.stripHeight(360))
        assertEquals(7, FrameStamp.sampleRow(360))
        assertEquals(43, FrameStamp.stripHeight(1080))
        assertEquals(22, FrameStamp.sampleRow(1080))
    }

    @Test
    fun roundTripEveryCounterAt640() {
        for (counter in 0..FrameStamp.COUNTER_MASK) assertEquals(counter, FrameStamp.decode(FrameStamp.renderRow(counter, 640), 640))
    }

    @Test
    fun roundTripAtCommonWidths() {
        for (width in intArrayOf(FrameStamp.MIN_WIDTH, 320, 426, 640, 854, 1280, 1920, 3840)) {
            repeat(200) {
                val counter = random.nextInt(0, 0x10000)
                assertEquals("width $width", counter, FrameStamp.decode(FrameStamp.renderRow(counter, width), width))
            }
        }
    }

    @Test
    fun counterWrapsModulo65536() {
        assertEquals(0, FrameStamp.decode(FrameStamp.renderRow(0x10000, 1280), 1280))
        assertEquals(5, FrameStamp.decode(FrameStamp.renderRow(0x10005, 1280), 1280))
        assertEquals(0xFFFF, FrameStamp.decode(FrameStamp.renderRow(-1, 1280), 1280))
        assertEquals(0x2345, FrameStamp.decode(FrameStamp.renderRow(0x7FFF2345, 1280), 1280))
    }

    @Test
    fun rejectsTooNarrowOrShortRows() {
        assertNull(FrameStamp.decode(FrameStamp.renderRow(7, 100), 100))
        assertNull(FrameStamp.decode(IntArray(10), 1280))
        assertNull(FrameStamp.decode(IntArray(1280), 1280))
        assertNull(FrameStamp.decode(IntArray(1280) { 255 }, 1280))
    }

    @Test
    fun survivesGaussianBlur() = check { row, _ -> StampChannel.blur(row, 3f) }

    @Test
    fun survivesDownscaleTo360p() = check(outWidth = 640) { row, _ -> StampChannel.scale(row, 640) }

    @Test
    fun survivesUpscaleTo1080p() = check(outWidth = 1920) { row, _ -> StampChannel.scale(row, 1920) }

    @Test
    fun survivesDownscaleToOddWidth() = check(outWidth = 853) { row, _ -> StampChannel.scale(row, 853) }

    @Test
    fun survivesNoiseSigma8() = check { row, r -> StampChannel.noise(row, 8f, r) }

    @Test
    fun survivesLimitedRangeAndGamma() {
        check { row, _ -> StampChannel.range(row) }
        check { row, _ -> StampChannel.gamma(StampChannel.range(row), 2.2f) }
        check { row, _ -> StampChannel.gamma(StampChannel.range(row), 0.45f) }
        // Heavy contrast loss: white and black only 60 levels apart.
        check { row, _ -> StampChannel.range(row, 100f, 160f) }
    }

    @Test
    fun survivesThreePercentShift() {
        check { row, _ -> StampChannel.shift(row, 38) }
        check { row, _ -> StampChannel.shift(row, -38) }
        check(outWidth = 640) { row, _ -> StampChannel.shift(StampChannel.scale(row, 640), 19) }
    }

    @Test
    fun survivesBlockQuantisation() {
        check { row, r -> StampChannel.blocks(row, 24f, r.nextInt(8)) }
        check(outWidth = 640) { row, r -> StampChannel.blocks(StampChannel.scale(row, 640), 24f, r.nextInt(8)) }
    }

    /** Everything at once, in the order of the real path, at both proxy sizes the monitor may see. */
    @Test
    fun survivesTheWholePath() {
        for (outWidth in intArrayOf(640, 1280, 1920)) {
            check(outWidth = outWidth) { row, r ->
                var x = StampChannel.blur(row, 1.5f)
                x = StampChannel.shift(x, (r.nextFloat() * 0.06f - 0.03f).times(row.size).toInt())
                x = StampChannel.scale(x, outWidth)
                x = StampChannel.range(x)
                x = StampChannel.blocks(x, 16f, r.nextInt(8))
                x = StampChannel.gamma(x, 0.8f + r.nextFloat() * 0.6f)
                StampChannel.noise(x, 8f, r)
            }
        }
    }

    private fun check(outWidth: Int = 1280, rows: Int = 500, corrupt: (FloatArray, Random) -> FloatArray) {
        repeat(rows) {
            val counter = random.nextInt(0, 0x10000)
            val luma = StampChannel.toLuma(corrupt(FrameStamp.renderRow(counter, 1280).toFloatArray(), random))
            assertEquals("counter $counter", counter, FrameStamp.decode(luma, outWidth))
        }
        assertTrue(rows > 0)
    }
}
