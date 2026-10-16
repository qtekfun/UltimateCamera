package com.qtekfun.ultimatecamera.core.gpu

import com.qtekfun.ultimatecamera.core.gpu.StampChannel.toFloatArray
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [FrameStamp.decode] may give up (null) on a badly damaged row, but it must never return a wrong counter: a wrong
 * counter would be a silently wrong latency sample. Each family runs thousands of random corruptions far beyond the
 * expected video path and counts wrong results, which must be zero. Null rates are printed (`STAMP_FP` lines in the test
 * report) for the record.
 */
class FrameStampFalsePositiveTest {
    private val random = Random(4242)

    /** The whole path with every parameter pushed past what a working link produces. */
    @Test
    fun heavyRandomPath() = family("heavy-path", 4000) { counter, r ->
        val outWidth = intArrayOf(426, 640, 854, 1280, 1920)[r.nextInt(5)]
        var x = StampChannel.row(counter, 1280)
        x = StampChannel.blur(x, r.nextFloat() * 8f)
        x = StampChannel.shift(x, ((r.nextFloat() * 0.16f - 0.08f) * 1280).toInt())
        x = StampChannel.scale(x, outWidth)
        val low = r.nextFloat() * 120f
        x = StampChannel.range(x, low, low + 20f + r.nextFloat() * 200f)
        x = StampChannel.blocks(x, 4f + r.nextFloat() * 60f, r.nextInt(8))
        x = StampChannel.gamma(x, 0.4f + r.nextFloat() * 2f)
        StampChannel.toLuma(StampChannel.noise(x, r.nextFloat() * 48f, r)).let { Trial(it, outWidth) }
    }

    /** Noise far above σ = 8 on an otherwise clean, limited-range row. */
    @Test
    fun strongNoise() = family("noise-sigma-16-64", 4000) { counter, r ->
        val x = StampChannel.range(StampChannel.row(counter, 1280))
        StampChannel.toLuma(StampChannel.noise(x, 16f + r.nextFloat() * 48f, r)).let { Trial(it, 1280) }
    }

    /** One to twelve whole data cells inverted (a bit error the markers cannot see; only the CRC can). */
    @Test
    fun invertedCells() = family("inverted-cells", 4000) { counter, r ->
        val cells = FrameStamp.encode(counter)
        repeat(1 + r.nextInt(12)) {
            val c = 8 + r.nextInt(32)
            cells[c] = !cells[c]
        }
        IntArray(640) { x -> if (cells[FrameStamp.cellAt(x, 640)]) 235 else 16 }.let { Trial(it, 640) }
    }

    /**
     * Left part of one stamp joined to the right part of another, cut inside the counter or check cells (cells 8..39),
     * as a torn or half-updated frame would show. Either of the two counters is accepted (the row matches one exactly
     * when they share the cells on one side of the cut); anything else is wrong.
     */
    @Test
    fun splicedStamps() = family("spliced", 4000) { counter, r ->
        val other = (counter + 1 + r.nextInt(0xFFFF)) and FrameStamp.COUNTER_MASK
        val otherRow = FrameStamp.renderRow(other, 1280)
        val row = FrameStamp.renderRow(counter, 1280)
        val cut = (8 * 1280 / 48) + r.nextInt(32 * 1280 / 48)
        for (x in cut until 1280) row[x] = otherRow[x]
        Trial(StampChannel.toLuma(StampChannel.noise(StampChannel.blur(row.toFloatArray(), 1f), 4f, r)), 1280, other)
    }

    /** A random segment overwritten by a flat level or noise (a macroblock lost and concealed). */
    @Test
    fun burstDamage() = family("burst", 4000) { counter, r ->
        val row = StampChannel.row(counter, 1280)
        val start = r.nextInt(1280)
        val length = 8 + r.nextInt(400)
        val level = r.nextFloat() * 255f
        val flat = r.nextBoolean()
        for (x in start until minOf(1280, start + length)) row[x] = if (flat) level else r.nextFloat() * 255f
        StampChannel.toLuma(row).let { Trial(it, 1280) }
    }

    /** No stamp at all: picture content (smooth random walks, stripes) and pure noise. */
    @Test
    fun noStamp() = family("no-stamp", 4000, expectNoValue = true) { _, r ->
        val row = FloatArray(1280)
        when (r.nextInt(3)) {
            0 -> {
                var v = r.nextFloat() * 255f
                for (x in row.indices) {
                    v = (v + StampChannel.gaussian(r) * 12f).coerceIn(0f, 255f)
                    row[x] = v
                }
            }
            1 -> {
                val period = 4 + r.nextInt(80)
                for (x in row.indices) row[x] = if ((x / period) % 2 == 0) 235f else 16f
            }
            else -> for (x in row.indices) row[x] = r.nextFloat() * 255f
        }
        StampChannel.toLuma(row).let { Trial(it, 1280) }
    }

    /**
     * Runs [trials] corruptions of random counters; asserts no wrong counter and prints the null rate. With
     * [expectNoValue] every non-null result counts as wrong.
     */
    private fun family(name: String, trials: Int, expectNoValue: Boolean = false, corrupt: (Int, Random) -> Trial) {
        var wrong = 0
        var nulls = 0
        repeat(trials) {
            val counter = random.nextInt(0, 0x10000)
            val trial = corrupt(counter, random)
            val decoded = FrameStamp.decode(trial.luma, trial.width)
            when {
                decoded == null -> nulls++
                expectNoValue || (decoded != counter && decoded != trial.alsoCorrect) -> wrong++
            }
        }
        println("STAMP_FP family=$name trials=$trials wrong=$wrong null=$nulls (${"%.1f".format(100.0 * nulls / trials)} %)")
        assertEquals("$name: wrong counters", 0, wrong)
    }

    /** A corrupted row of a frame [width] px wide; [alsoCorrect] is a second counter the row may legitimately hold. */
    private class Trial(val luma: IntArray, val width: Int, val alsoCorrect: Int = -1)
}
