package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePortraitTest {
    // --- Focus selection and depth at a point (tap-to-focus) ---

    /** A subject (2 D, 0.5 m) on the left third, the background (0.2 D, 5 m) elsewhere, a hole in the top-right corner. */
    private val depth = Plane(60, 40).apply {
        for (y in 0 until 40) {
            for (x in 0 until 60) {
                this[x, y] = when {
                    x < 20 -> 2f
                    x >= 56 && y < 4 -> Bokeh.INVALID
                    else -> 0.2f
                }
            }
        }
    }

    @Test
    fun aTapOnTheSubjectFocusesOnItAndOnTheBackgroundFocusesThere() {
        assertEquals(2f, Bokeh.focusAt(depth, 0.15f, 0.5f), 0f)
        assertEquals(0.2f, Bokeh.focusAt(depth, 0.7f, 0.5f), 0f)
        // The centre (the default before any tap) is background here.
        assertEquals(0.2f, Bokeh.focusAt(depth, 0.5f, 0.5f), 0f)
    }

    @Test
    fun theFocusIsTheMedianOfTheWindowSoAnEdgeTapPicksTheMajority() {
        // Centred on column 21: two of the 7-wide window's columns are subject, five background.
        assertEquals(0.2f, Bokeh.focusAt(depth, 21.5f / 59f, 0.5f), 0f)
        // Centred on column 19: four of seven are subject.
        assertEquals(2f, Bokeh.focusAt(depth, 19.5f / 59f, 0.5f), 0f)
    }

    @Test
    fun aTapOnAHoleFallsBackToTheWiderWindow() {
        assertEquals(Bokeh.INVALID, Bokeh.focusInverseDepth(depth, 1f, 0f), 0f)
        assertEquals(0.2f, Bokeh.focusAt(depth, 1f, 0f), 0f)
        assertEquals(Bokeh.INVALID, Bokeh.focusAt(Plane(4, 4).apply { data.fill(Bokeh.INVALID) }, 0.5f, 0.5f), 0f)
    }

    @Test
    fun pointsOutsideTheMapAreClampedToItsBorder() {
        assertEquals(2f, Bokeh.focusAt(depth, -0.3f, 0.5f), 0f)
        assertEquals(0.2f, Bokeh.focusAt(depth, 1.5f, 1.5f), 0f)
    }

    // --- Circle of confusion ---

    @Test
    fun cocScaleTimesTheDioptreGapIsTheCoc() {
        for (n in Bokeh.STOPS) {
            val scale = Bokeh.cocScale(n, 480)
            assertEquals(Bokeh.cocPx(0.2f, 2f, n, 480), scale * 1.8f, 1e-4f)
        }
        assertEquals(0f, Bokeh.cocScale(0f, 480), 0f)
        // Proportional to the image width: the live copy blurs like the capture, just smaller.
        assertEquals(Bokeh.cocScale(2f, 1024) * 480 / 1024, Bokeh.cocScale(2f, 480), 1e-4f)
    }

    // --- Size and rate of the live preview ---

    @Test
    fun planKeepsThePreviewAspectWithEvenSizes() {
        val plan = LivePortraitPlan.of(1440, 1080, modest = false)
        assertEquals(480, plan.width)
        assertEquals(360, plan.height)
        val wide = LivePortraitPlan.of(1920, 1080, modest = false)
        assertEquals(270 and 1.inv(), wide.height)
        assertEquals(0, wide.width % 2)
        assertEquals(0, wide.height % 2)
    }

    @Test
    fun modestPhonesBlurASmallerCopyLessOftenWithLessDepth() {
        val normal = LivePortraitPlan.of(1440, 1080, modest = false)
        val modest = LivePortraitPlan.of(1440, 1080, modest = true)
        assertEquals(320, modest.width)
        assertTrue(modest.blurEvery > normal.blurEvery)
        assertTrue(modest.depthEvery > normal.depthEvery)
        assertTrue(modest.minDepthIntervalNanos > normal.minDepthIntervalNanos)
        assertTrue(modest.maxBlurEvery >= modest.blurEvery)
    }

    @Test
    fun aSmallPreviewIsNeverUpscaledAndTheRadiusScalesWithTheCopy() {
        val plan = LivePortraitPlan.of(320, 240, modest = false)
        assertEquals(320, plan.width)
        assertEquals(240, plan.height)
        assertEquals(DepthBlur.MAX_RADIUS * 320 / 1024, plan.maxRadius, 1e-4f)
        assertThrows(IllegalArgumentException::class.java) { LivePortraitPlan.of(0, 240, modest = false) }
    }

    // --- Frame dropping: the blur pacer ---

    private fun pattern(pacer: LivePortraitPacer, frames: Int) = (0 until frames).map { pacer.next() }

    @Test
    fun theBlurRunsOnOneFrameInTheBaseStride() {
        val pacer = LivePortraitPacer(baseStride = 2, maxStride = 4, budgetNanos = 20_000_000)
        assertEquals(listOf(true, false, true, false, true, false), pattern(pacer, 6))
    }

    @Test
    fun underLoadTheBlurRefreshesLessOftenButNeverStops() {
        val pacer = LivePortraitPacer(baseStride = 2, maxStride = 4, budgetNanos = 20_000_000)
        repeat(LivePortraitPacer.WINDOW) { pacer.record(blurred = true, renderNanos = 40_000_000) }
        assertEquals(3, pacer.stride)
        repeat(LivePortraitPacer.WINDOW * 4) { pacer.record(blurred = true, renderNanos = 40_000_000) }
        assertEquals("bounded by the plan", 4, pacer.stride)
        assertTrue("still blurs sometimes", pattern(pacer, 8).count { it } == 2)
    }

    @Test
    fun framesThatDidNotBlurDoNotCount() {
        val pacer = LivePortraitPacer(baseStride = 2, maxStride = 4, budgetNanos = 20_000_000)
        repeat(100) { pacer.record(blurred = false, renderNanos = 90_000_000) }
        assertEquals(2, pacer.stride)
    }

    @Test
    fun aShortSpikeDoesNotSlowTheBlur() {
        val pacer = LivePortraitPacer(baseStride = 2, maxStride = 4, budgetNanos = 20_000_000)
        pacer.record(blurred = true, renderNanos = 80_000_000)
        repeat(LivePortraitPacer.WINDOW - 1) { pacer.record(blurred = true, renderNanos = 5_000_000) }
        assertEquals(2, pacer.stride)
    }

    @Test
    fun whenTheLoadGoesTheRateComesBack() {
        val pacer = LivePortraitPacer(baseStride = 2, maxStride = 4, budgetNanos = 20_000_000)
        repeat(LivePortraitPacer.WINDOW) { pacer.record(blurred = true, renderNanos = 40_000_000) }
        assertEquals(3, pacer.stride)
        repeat(LivePortraitPacer.RECOVERY_FRAMES - 1) { pacer.record(blurred = true, renderNanos = 5_000_000) }
        assertEquals("not before enough cheap frames", 3, pacer.stride)
        pacer.record(blurred = true, renderNanos = 5_000_000)
        assertEquals(2, pacer.stride)
        repeat(LivePortraitPacer.RECOVERY_FRAMES * 2) { pacer.record(blurred = true, renderNanos = 5_000_000) }
        assertEquals("never under the plan's rate", 2, pacer.stride)
    }

    @Test
    fun pacerBudgetIsAShareOfTheFrameInterval() {
        val plan = LivePortraitPlan.of(1440, 1080, modest = false)
        val pacer = LivePortraitPacer.forPlan(plan, fps = 30)
        assertEquals(plan.blurEvery, pacer.stride)
        // 60 % of 33.3 ms = 20 ms: a 25 ms blurred frame is over budget.
        repeat(LivePortraitPacer.WINDOW) { pacer.record(blurred = true, renderNanos = 25_000_000) }
        assertEquals(plan.blurEvery + 1, pacer.stride)
        assertThrows(IllegalArgumentException::class.java) { LivePortraitPacer(0, 2, 1) }
    }

    // --- Frame dropping: the depth gate ---

    @Test
    fun theDepthGateDropsFramesWhileTheWorkerIsBusy() {
        val gate = LiveDepthGate(minIntervalNanos = 0)
        assertTrue(gate.tryAcquire(0))
        assertFalse(gate.tryAcquire(1_000_000))
        assertFalse(gate.tryAcquire(2_000_000))
        assertEquals(2, gate.dropped)
        gate.release()
        assertTrue(gate.tryAcquire(3_000_000))
    }

    @Test
    fun theDepthGateKeepsAMinimumInterval() {
        val gate = LiveDepthGate(minIntervalNanos = 100_000_000)
        assertTrue(gate.tryAcquire(1_000_000_000))
        gate.release()
        assertFalse("too soon", gate.tryAcquire(1_050_000_000))
        assertTrue(gate.tryAcquire(1_100_000_000))
        assertEquals(1, gate.dropped)
    }
}
