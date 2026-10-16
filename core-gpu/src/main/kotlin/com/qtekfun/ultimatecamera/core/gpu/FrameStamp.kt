package com.qtekfun.ultimatecamera.core.gpu

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Machine-readable frame counter drawn into the proxy video for glass-to-glass latency measurement (research doc
 * `webrtc-android.md` §5). The camera phone draws it with [com.qtekfun.ultimatecamera.core.gpu.gl.FrameStampRenderer];
 * the monitor phone reads one row of the decoded frame and calls [decode].
 *
 * ## Geometry
 * A strip over the top [STRIP_HEIGHT_FRACTION] (4 %) of the frame, full width, split into [CELLS] equally wide black or
 * white cells (vertically uniform). Cell `i` covers the pixels whose centre `x + 0.5` lies in `[i, i + 1) * width / CELLS`.
 * The reader samples the row at [sampleRow] (y = round(0.02 * height) from the top, the strip's vertical centre).
 *
 * ## Cell layout (48 cells, left to right)
 * ```
 * cells  0..3   quiet margin      0000             absorbs a horizontal shift; never read
 * cells  4..7   start marker      1101
 * cells  8..23  counter           16 bits, MSB first (counter and 0xFFFF; wraps)
 * cells 24..39  check             CRC-16 of the counter (poly 0x51E9, init 0xFFFF, see [crc16]), MSB first
 * cells 40..43  end marker        1011
 * cells 44..47  quiet margin      0000
 * ```
 * 1 = white (full scale), 0 = black. The markers give the decoder its black and white reference levels and, being
 * asymmetric and next to black margins, pass only at the right alignment (a shift by one or more cells breaks them).
 *
 * ## Why it survives the video path
 * - Cells are 1/48 of the width: 26.7 px at 1280, 13.3 px at 640 (360p), 40 px at 1920; wider than an 8×8 coding block
 *   at 720p, and about 1.7 blocks at 360p, and far wider than any scaler or deblocking filter footprint.
 * - Black and white carry no chroma, so 4:2:0 subsampling does not touch them; only luma is read.
 * - The threshold is adaptive (midpoint of the marker cells' mean levels), so limited range (16..235), gamma and
 *   contrast loss do not matter as long as white and black differ by at least [MIN_CONTRAST] levels.
 * - Each cell is decided by a majority vote over the pixels of its central half; a cell whose vote is not clear (less
 *   than 3/4 of the samples agreeing) rejects the alignment, so ringing and blocking at edges are ignored or reported
 *   as null rather than misread.
 * - A horizontal shift of up to [MAX_SHIFT_FRACTION] (6 %) of the width is found by trying alignments in steps of 1/8
 *   cell. If two alignments decode to different valid counters, the result is null.
 * - The CRC-16 detects every error of up to 6 cells and every burst of up to 16 cells; anything else passes with
 *   probability about 2^-16, on top of passing both markers and every confident-vote check.
 *
 * ## Cost
 * [decode] tries 49 alignments (±6 % in 1/8-cell steps); each reads 48 cells (8 marker means, 40 votes) over half a
 * cell of pixels: about 31 000 integer additions and comparisons at 1280 px, independent of the counter. That is about
 * 12 µs per row at 640 px on a desktop JVM and well under 0.1 ms on a modest phone. It allocates nothing except the
 * boxed return value that the `Int?` contract implies.
 */
object FrameStamp {
    /** Number of cells across the full width. */
    const val CELLS = 48

    /** Counter values are taken modulo 2^16. */
    const val COUNTER_MASK = 0xFFFF

    /** Height of the strip as a fraction of the frame height, from the top. */
    const val STRIP_HEIGHT_FRACTION = 0.04f

    /** Row the reader samples, as a fraction of the frame height from the top: the strip's vertical centre. */
    const val SAMPLE_ROW_FRACTION = 0.02f

    /** Largest horizontal shift of the strip, as a fraction of the width, that [decode] searches. */
    const val MAX_SHIFT_FRACTION = 0.06f

    /** Smallest difference, in luma levels, between the white and black marker means that [decode] accepts. */
    const val MIN_CONTRAST = 32f

    /** Smallest width that keeps cells at least 4 px wide. */
    const val MIN_WIDTH = CELLS * 4

    private const val MARGIN = 4
    private const val START = MARGIN
    private const val PAYLOAD = START + 4
    private const val CHECK = PAYLOAD + 16
    private const val END = CHECK + 16
    private const val LAST_READ = END + 3

    /** Start marker 1101 at cells 4..7 and end marker 1011 at cells 40..43, as cell bits (bit i = cell i). */
    private const val MARKER_MASK = (0xFL shl START) or (0xFL shl END)
    private const val MARKER_BITS = (0b1011L shl START) or (0b1101L shl END)
    private const val FAIL = -1
    private const val VOTE_CONFIDENCE = 0.75f
    private const val ALIGNMENT_STEPS_PER_CELL = 8
    private const val CRC_POLYNOMIAL = 0x51E9

    /** Height in pixels of the strip in a frame [frameHeight] pixels high (at least 1). */
    fun stripHeight(frameHeight: Int): Int = max(1, (frameHeight * STRIP_HEIGHT_FRACTION).roundToInt())

    /** Row, counted from the top, that the reader passes to [decode] for a frame [frameHeight] pixels high. */
    fun sampleRow(frameHeight: Int): Int = (frameHeight * SAMPLE_ROW_FRACTION).roundToInt()

    /**
     * The cells for [counter] packed into a Long, bit `i` = cell `i` (1 = white). Allocation-free; this is what the
     * renderer uploads every frame.
     */
    fun cellBits(counter: Int): Long {
        val value = counter and COUNTER_MASK
        val crc = crc16(value)
        var bits = MARKER_BITS
        for (j in 0 until 16) {
            bits = bits or (((value ushr (15 - j)) and 1).toLong() shl (PAYLOAD + j))
            bits = bits or (((crc ushr (15 - j)) and 1).toLong() shl (CHECK + j))
        }
        return bits
    }

    /** The [CELLS] cells for [counter], left to right (true = white). */
    fun encode(counter: Int): BooleanArray {
        val bits = cellBits(counter)
        return BooleanArray(CELLS) { ((bits ushr it) and 1L) != 0L }
    }

    /** Cell under pixel column [x] of a strip [width] pixels wide, with the same float maths as the fragment shader. */
    fun cellAt(x: Int, width: Int): Int = ((x + 0.5f) * (CELLS.toFloat() / width)).toInt().coerceIn(0, CELLS - 1)

    /** CPU rendering of one strip row for [counter] at [width] px, as luma 0 (black) or 255 (white). For tests. */
    fun renderRow(counter: Int, width: Int): IntArray {
        val bits = cellBits(counter)
        return IntArray(width) { x -> if (((bits ushr cellAt(x, width)) and 1L) != 0L) 255 else 0 }
    }

    /**
     * Reads the counter from [luma] (0..255 samples of one row, see [sampleRow]) of a frame [width] pixels wide. Returns
     * the counter modulo 2^16, or null when no alignment passes the markers, the confidence votes and the CRC, or when
     * alignments disagree. Matches the shape of core-net's `StampDecoder.decode`.
     */
    fun decode(luma: IntArray, width: Int): Int? {
        if (width < MIN_WIDTH || luma.size < width) return null
        val pitch = width.toFloat() / CELLS
        val step = pitch / ALIGNMENT_STEPS_PER_CELL
        val steps = ceil(MAX_SHIFT_FRACTION * width / step).toInt()
        var found = FAIL
        for (k in -steps..steps) {
            val value = Reader.decodeAt(luma, width, k * step, pitch)
            if (value != FAIL && found != FAIL && value != found) return null
            if (value != FAIL) found = value
        }
        return if (found == FAIL) null else found
    }

    /** Alignment search and per-cell sampling behind [decode]. */
    private object Reader {
        /** Decodes assuming the strip is shifted right by [offset] pixels; [FAIL] if anything does not check out. */
        fun decodeAt(luma: IntArray, width: Int, offset: Float, pitch: Float): Int {
            if (windowStart(START, offset, pitch) < 0 || windowEnd(LAST_READ, offset, pitch) > width) return FAIL
            var white = 0f
            var black = 0f
            for (cell in START..LAST_READ) {
                if (((MARKER_MASK ushr cell) and 1L) == 0L) continue
                val mean = cellMean(luma, cell, offset, pitch)
                if (((MARKER_BITS ushr cell) and 1L) != 0L) white += mean else black += mean
            }
            // 6 white and 2 black marker cells.
            white /= 6f
            black /= 2f
            if (white - black < MIN_CONTRAST) return FAIL
            val threshold = (white + black) * 0.5f
            var bits = 0L
            for (cell in START..LAST_READ) {
                val vote = cellVote(luma, cell, offset, pitch, threshold)
                if (vote == FAIL) return FAIL
                bits = bits or (vote.toLong() shl cell)
            }
            if ((bits and MARKER_MASK) != MARKER_BITS) return FAIL
            var value = 0
            var crc = 0
            for (j in 0 until 16) {
                value = (value shl 1) or ((bits ushr (PAYLOAD + j)) and 1L).toInt()
                crc = (crc shl 1) or ((bits ushr (CHECK + j)) and 1L).toInt()
            }
            return if (crc16(value) == crc) value else FAIL
        }

        /** First pixel sampled for [cell]: pixel centres in the central half of the cell. */
        fun windowStart(cell: Int, offset: Float, pitch: Float): Int = ceil(offset + (cell + 0.25f) * pitch - 0.5f).toInt()

        /** One past the last pixel sampled for [cell]. */
        fun windowEnd(cell: Int, offset: Float, pitch: Float): Int =
            max(windowStart(cell, offset, pitch) + 1, ceil(offset + (cell + 0.75f) * pitch - 0.5f).toInt())

        fun cellMean(luma: IntArray, cell: Int, offset: Float, pitch: Float): Float {
            val from = windowStart(cell, offset, pitch)
            val to = windowEnd(cell, offset, pitch)
            var sum = 0
            for (x in from until to) sum += luma[x]
            return sum.toFloat() / (to - from)
        }

        /** 1 or 0 when at least [VOTE_CONFIDENCE] of the cell's samples agree, [FAIL] otherwise. */
        fun cellVote(luma: IntArray, cell: Int, offset: Float, pitch: Float, threshold: Float): Int {
            val from = windowStart(cell, offset, pitch)
            val to = windowEnd(cell, offset, pitch)
            var above = 0
            for (x in from until to) if (luma[x] > threshold) above++
            val n = (to - from).toFloat()
            return when {
                above >= n * VOTE_CONFIDENCE -> 1
                above <= n * (1f - VOTE_CONFIDENCE) -> 0
                else -> FAIL
            }
        }
    }

    /**
     * CRC-16 of the 16-bit [value], MSB first, init 0xFFFF, no reflection, generator x^16 + 0x51E9. The polynomial was
     * chosen by exhaustive search over all 16-bit CRCs for this exact 16-bit message: the 32-cell word then has minimum
     * Hamming distance 7 (the best any 16-bit CRC reaches here; CCITT 0x1021 only reaches 4), so up to 6 wrong cells are
     * always detected.
     */
    internal fun crc16(value: Int): Int {
        var crc = 0xFFFF
        for (i in 15 downTo 0) {
            val bit = ((value ushr i) and 1) xor ((crc ushr 15) and 1)
            crc = (crc shl 1) and 0xFFFF
            if (bit != 0) crc = crc xor CRC_POLYNOMIAL
        }
        return crc
    }
}
