package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Portrait in video (D89): quality rungs, the step-down governor, temporal smoothing of depth and focus. */
class VideoPortraitTest {
    private val frame30 = 1_000_000_000L / 30
    private val frame60 = 1_000_000_000L / 60
    private val cheap = frame30 / 4
    private val slow = frame30 // 100 % of the interval, over the 75 % budget

    // --- Rungs and plans ---

    @Test
    fun rungsGoDownToOffAndStayThere() {
        assertEquals(VideoBlurLevel.MEDIUM, VideoBlurLevel.HIGH.lower())
        assertEquals(VideoBlurLevel.LOW, VideoBlurLevel.MEDIUM.lower())
        assertEquals(VideoBlurLevel.OFF, VideoBlurLevel.LOW.lower())
        assertEquals(VideoBlurLevel.OFF, VideoBlurLevel.OFF.lower())
        // Each rung is lighter than the one above it.
        val on = VideoBlurLevel.entries - VideoBlurLevel.OFF
        on.zipWithNext().forEach { (a, b) -> assertTrue("$a > $b", a.width > b.width && a.samples > b.samples) }
    }

    @Test
    fun theStartRungFollowsThePixelRateAndModestPhonesStartLow() {
        assertEquals(VideoBlurLevel.HIGH, VideoBlurLevel.start(1920, 1080, 30, modest = false))
        assertEquals(VideoBlurLevel.HIGH, VideoBlurLevel.start(1280, 720, 60, modest = false))
        assertEquals(VideoBlurLevel.MEDIUM, VideoBlurLevel.start(1920, 1080, 60, modest = false))
        assertEquals(VideoBlurLevel.MEDIUM, VideoBlurLevel.start(3840, 2160, 30, modest = false))
        assertEquals(VideoBlurLevel.LOW, VideoBlurLevel.start(3840, 2160, 60, modest = false))
        assertEquals(VideoBlurLevel.LOW, VideoBlurLevel.start(1280, 720, 30, modest = true))
    }

    @Test
    fun aPlanKeepsTheVideoAspectAtTheRungWidthAndScalesTheRadius() {
        val plan = requireNotNull(VideoPortraitPlan.of(VideoBlurLevel.HIGH, 3840, 2160))
        assertEquals(960, plan.width)
        assertEquals(540, plan.height)
        assertEquals(48, plan.samples)
        assertEquals(DepthBlur.MAX_RADIUS * 960 / LivePortraitPlan.CAPTURE_WIDTH, plan.maxRadius, 1e-4f)
        // Never wider than the video, even sizes.
        val small = requireNotNull(VideoPortraitPlan.of(VideoBlurLevel.HIGH, 641, 361))
        assertEquals(640, small.width)
        assertEquals(0, small.height % 2)
        assertEquals(426, requireNotNull(VideoPortraitPlan.of(VideoBlurLevel.LOW, 1920, 1080)).width)
        assertNull(VideoPortraitPlan.of(VideoBlurLevel.OFF, 1920, 1080))
        assertThrows(IllegalArgumentException::class.java) { VideoPortraitPlan.of(VideoBlurLevel.LOW, 0, 1080) }
    }

    // --- Step-down governor ---

    @Test
    fun cheapFramesKeepTheRung() {
        val g = VideoBlurGovernor(VideoBlurLevel.HIGH, frame30)
        repeat(300) { assertFalse(g.record(cheap, 0)) }
        assertEquals(VideoBlurLevel.HIGH, g.level)
        assertNull(g.cause)
    }

    @Test
    fun aFewSlowFramesPerWindowAreToleratedMoreStepDown() {
        val g = VideoBlurGovernor(VideoBlurLevel.HIGH, frame30)
        repeat(VideoBlurGovernor.WARMUP) { g.record(slow, 0) }
        // Three slow frames in each window of 30: tolerated, window after window.
        repeat(10) {
            repeat(VideoBlurGovernor.SLOW_LIMIT) { assertFalse(g.record(slow, 0)) }
            repeat(VideoBlurGovernor.WINDOW - VideoBlurGovernor.SLOW_LIMIT) { assertFalse(g.record(cheap, 0)) }
        }
        assertEquals(VideoBlurLevel.HIGH, g.level)
        // A fourth one in the same window steps down.
        repeat(VideoBlurGovernor.SLOW_LIMIT) { g.record(slow, 0) }
        assertTrue(g.record(slow, 0))
        assertEquals(VideoBlurLevel.MEDIUM, g.level)
        assertEquals(VideoBlurCause.SLOW_FRAMES, g.cause)
    }

    @Test
    fun theBudgetIsThreeQuartersOfTheFrameInterval() {
        val g = VideoBlurGovernor(VideoBlurLevel.LOW, frame60)
        repeat(VideoBlurGovernor.WARMUP) { g.record(0, 0) }
        // 70 % of the 60 fps interval is fine, 80 % is slow.
        repeat(100) { assertFalse(g.record(frame60 * 70 / 100, 0)) }
        repeat(VideoBlurGovernor.SLOW_LIMIT) { g.record(frame60 * 80 / 100, 0) }
        assertTrue(g.record(frame60 * 80 / 100, 0))
    }

    @Test
    fun aDroppedFrameStepsDownAtOnceAndTheWarmUpIsNotJudged() {
        val g = VideoBlurGovernor(VideoBlurLevel.HIGH, frame30)
        // The first frames of a rung (new targets, first draw) are not judged, drops included.
        repeat(VideoBlurGovernor.WARMUP) { assertFalse(g.record(slow * 3, 2)) }
        assertTrue(g.record(cheap, 1))
        assertEquals(VideoBlurLevel.MEDIUM, g.level)
        assertEquals(VideoBlurCause.DROPPED_FRAMES, g.cause)
        // A new warm-up after the change.
        repeat(VideoBlurGovernor.WARMUP) { assertFalse(g.record(cheap, 1)) }
        assertTrue(g.record(cheap, 1))
        assertEquals(VideoBlurLevel.LOW, g.level)
    }

    @Test
    fun itStepsDownToOffAndThenStaysOffWithoutJudging() {
        val g = VideoBlurGovernor(VideoBlurLevel.LOW, frame30)
        repeat(VideoBlurGovernor.WARMUP) { g.record(cheap, 0) }
        assertTrue(g.record(cheap, 1))
        assertEquals(VideoBlurLevel.OFF, g.level)
        repeat(50) { assertFalse(g.record(slow, 5)) }
        assertFalse(g.fail())
    }

    @Test
    fun aNewTakeRestartsTheWindowAndTheWarmUpButKeepsTheRung() {
        val g = VideoBlurGovernor(VideoBlurLevel.MEDIUM, frame30)
        repeat(VideoBlurGovernor.WARMUP) { g.record(cheap, 0) }
        repeat(VideoBlurGovernor.SLOW_LIMIT) { g.record(slow, 0) }
        g.restart()
        // The slow frames before the restart no longer count, and the warm-up is back.
        repeat(VideoBlurGovernor.WARMUP) { assertFalse(g.record(slow, 3)) }
        repeat(VideoBlurGovernor.SLOW_LIMIT) { assertFalse(g.record(slow, 0)) }
        assertEquals(VideoBlurLevel.MEDIUM, g.level)
    }

    @Test
    fun aGpuFailureTurnsItOff() {
        val g = VideoBlurGovernor(VideoBlurLevel.HIGH, frame30)
        assertTrue(g.fail())
        assertEquals(VideoBlurLevel.OFF, g.level)
        assertEquals(VideoBlurCause.GPU_FAILURE, g.cause)
        assertThrows(IllegalArgumentException::class.java) { VideoBlurGovernor(VideoBlurLevel.HIGH, 0) }
    }

    // --- Temporal smoothing of depth ---

    private fun plane(vararg values: Float) = Plane(values.size, 1, values.copyOf())

    @Test
    fun theFirstMapIsTakenAsItIsAndNeverShared() {
        val f = DepthTemporalFilter()
        val input = plane(1f, 2f, Bokeh.INVALID)
        val out = f.apply(input)
        assertArrayEquals(input.data, out.data, 0f)
        assertNotSame(input.data, out.data)
    }

    @Test
    fun noiseIsDampedAndARealMoveIsFollowedFast() {
        val f = DepthTemporalFilter()
        f.apply(plane(1f, 1f))
        // +0.2 D is noise (slow alpha), +2 D is something that moved (fast alpha).
        val out = f.apply(plane(1.2f, 3f))
        assertEquals(1f + 0.2f * DepthTemporalFilter.SLOW_ALPHA, out[0, 0], 1e-5f)
        assertEquals(1f + 2f * DepthTemporalFilter.FAST_ALPHA, out[1, 0], 1e-5f)
    }

    @Test
    fun jitterAroundAValueSettlesInsteadOfFlickering() {
        val f = DepthTemporalFilter()
        var out = f.apply(plane(1f))
        var maxStep = 0f
        repeat(40) { i ->
            val previous = out[0, 0]
            out = f.apply(plane(if (i % 2 == 0) 1.3f else 0.7f))
            maxStep = maxOf(maxStep, kotlin.math.abs(out[0, 0] - previous))
        }
        // The raw input swings by 0.6 D every update; the smoothed value by far less.
        assertTrue("step $maxStep", maxStep < 0.3f)
        assertEquals(1f, out[0, 0], 0.2f)
    }

    @Test
    fun aLostPixelHoldsItsDepthForAFewUpdatesThenHasNone() {
        val f = DepthTemporalFilter()
        f.apply(plane(2f))
        repeat(DepthTemporalFilter.HOLD_UPDATES) { assertEquals(2f, f.apply(plane(Bokeh.INVALID))[0, 0], 0f) }
        assertEquals(Bokeh.INVALID, f.apply(plane(Bokeh.INVALID))[0, 0], 0f)
        // Depth coming back is taken at once (nothing to smooth from), and still nothing from nothing.
        assertEquals(0.5f, f.apply(plane(0.5f))[0, 0], 0f)
        val g = DepthTemporalFilter()
        g.apply(plane(Bokeh.INVALID))
        assertEquals(Bokeh.INVALID, g.apply(plane(Bokeh.INVALID))[0, 0], 0f)
    }

    @Test
    fun aHoldStartsAgainOnceThePixelHasDepthAgain() {
        val f = DepthTemporalFilter(holdUpdates = 1)
        f.apply(plane(2f))
        assertEquals(2f, f.apply(plane(Bokeh.INVALID))[0, 0], 0f)
        f.apply(plane(2f))
        assertEquals(2f, f.apply(plane(Bokeh.INVALID))[0, 0], 0f)
    }

    @Test
    fun aNewSizeOrAResetStartsAfresh() {
        val f = DepthTemporalFilter()
        f.apply(plane(1f, 1f))
        assertArrayEquals(floatArrayOf(3f, 3f, 3f), f.apply(plane(3f, 3f, 3f)).data, 0f)
        f.reset()
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.1f), f.apply(plane(0.1f, 0.1f, 0.1f)).data, 0f)
    }

    // --- Temporal smoothing of the focus ---

    @Test
    fun theFirstFocusIsTakenAndSmallJitterIsIgnored() {
        val s = FocusSmoother()
        assertEquals(Bokeh.INVALID, s.next(Bokeh.INVALID, newPoint = false), 0f)
        assertEquals(1f, s.next(1f, newPoint = false), 0f)
        assertEquals(1f, s.next(1f + FocusSmoother.DEADBAND / 2, newPoint = false), 0f)
        assertEquals(1f, s.next(1f - FocusSmoother.DEADBAND / 2, newPoint = false), 0f)
    }

    @Test
    fun aLargerChangeIsFollowedGently() {
        val s = FocusSmoother()
        s.next(1f, newPoint = false)
        assertEquals(1f + FocusSmoother.SETTLE_ALPHA, s.next(2f, newPoint = false), 1e-5f)
        // A point without depth keeps the last focus.
        assertEquals(1f + FocusSmoother.SETTLE_ALPHA, s.next(Bokeh.INVALID, newPoint = false), 1e-5f)
    }

    @Test
    fun aTapRacksFasterForAFewUpdatesThenSettles() {
        val s = FocusSmoother()
        s.next(0.2f, newPoint = false)
        var focus = s.next(2f, newPoint = true)
        assertEquals(0.2f + 1.8f * FocusSmoother.RACK_ALPHA, focus, 1e-5f)
        repeat(FocusSmoother.RACK_UPDATES - 1) { focus = s.next(2f, newPoint = false) }
        // After the rack the focus is within a few hundredths of the target.
        assertEquals(2f, focus, 0.05f)
        val before = focus
        assertEquals(before + (2f - before) * if (2f - before >= FocusSmoother.DEADBAND) FocusSmoother.SETTLE_ALPHA else 0f, s.next(2f, false), 1e-5f)
        s.reset()
        assertEquals(Bokeh.INVALID, s.focus, 0f)
    }

    // --- Shader ---

    @Test
    fun theCompositeShaderMarksNoDepthLikeTheRestAndKeepsSharpPixelsUntouched() {
        val src = VideoPortraitShaders.COMPOSITE
        assertTrue(src.contains("samplerExternalOES uCamera"))
        assertTrue(src.contains("inverse <= ${Bokeh.INVALID}"))
        assertTrue(src.contains("radius - ${VideoPortraitShaders.SHARP_RADIUS}"))
        assertTrue(src.contains("discard"))
        assertTrue(VideoPortraitShaders.VERTEX.contains("out vec2 vUv"))
        assertFalse(src.contains("$"))
    }
}
