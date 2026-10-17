package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BracketPlanTest {
    @Test
    fun manualBracketScalesTimeWithinTheHandHeldLimit() {
        // Metered 1/120 s at ISO 100; -2/0/+2 EV.
        val plan = BracketCaptureStream.manualPlan(listOf(-2.0, 0.0, 2.0), 8_333_333L, 100, 10_000L..1_000_000_000L, 50..6400)
        assertEquals(2_083_333L, plan[0].first)
        assertEquals(100, plan[0].second)
        assertEquals(0.25, plan[0].third, 1e-6)
        assertEquals(Triple(8_333_333L, 100, 1.0), plan[1])
        assertEquals(33_333_332L, plan[2].first)
        assertEquals(4.0, plan[2].third, 1e-6)
    }

    @Test
    fun compensationExposureFollowsTheSteps() {
        assertEquals(4.0, BracketCaptureStream.compensationExposure(6, 1.0 / 3), 1e-9)
        assertEquals(0.25, BracketCaptureStream.compensationExposure(-4, 0.5), 1e-9)
        assertEquals("no step reported: the metered exposure", 1.0, BracketCaptureStream.compensationExposure(3, 0.0), 1e-9)
    }

    @Test
    fun timeBeyondTheLimitGoesToIso() {
        // 1/15 s metered: +2 EV wants 0.27 s, capped at 1/8 s; ISO doubles-ish to make up the rest.
        val plan = BracketCaptureStream.manualPlan(listOf(2.0), 66_666_666L, 200, 10_000L..1_000_000_000L, 50..6400).single()
        assertEquals(BracketCaptureStream.MAX_EXPOSURE_NS, plan.first)
        assertEquals(426, plan.second)
        assertEquals(4.0, plan.third, 0.01)
    }

    @Test
    fun clampedIsoReportsTheRealExposure() {
        val plan = BracketCaptureStream.manualPlan(listOf(2.0), 100_000_000L, 3200, 10_000L..1_000_000_000L, 50..3200).single()
        assertEquals(BracketCaptureStream.MAX_EXPOSURE_NS, plan.first)
        assertEquals(3200, plan.second)
        assertEquals("only the time grew", 1.25, plan.third, 1e-6)
        // Without ranges the metered ISO is kept and the time capped.
        val bare = BracketCaptureStream.manualPlan(listOf(-1.0, 3.0), 50_000_000L, 400, null, null)
        assertEquals(25_000_000L, bare[0].first)
        assertEquals(400, bare[0].second)
        assertEquals(BracketCaptureStream.MAX_EXPOSURE_NS, bare[1].first)
        assertEquals(400, bare[1].second)
        // The camera's shortest time wins on a dark minus bracket.
        assertEquals(10_000L, BracketCaptureStream.manualPlan(listOf(-4.0), 20_000L, 100, 10_000L..1_000_000_000L, 50..6400).single().first)
    }

    @Test
    fun bracketSizesStayWithinTheFusionBudgetAndTheJpegAspect() {
        val yuv = listOf(
            PixelSize(4080, 3072),
            PixelSize(4000, 3000),
            PixelSize(4000, 2250),
            PixelSize(3840, 2160),
            PixelSize(1920, 1440),
            PixelSize(8160, 6144)
        )
        val jpeg = listOf(PixelSize(8160, 6144), PixelSize(4080, 3072))
        val preview = listOf(PixelSize(1440, 1080), PixelSize(1920, 1080))
        val sizes = BracketCaptureStream.sizes(yuv, jpeg, preview)
        // 8160x6144 and 4080x3072 (12.53 MP) are over 12.5 MP; 4000x3000 is the largest 4:3 one within it.
        assertEquals(PhotoSizes(PixelSize(4000, 3000), PixelSize(1440, 1080)), sizes)
        // No YUV size with the JPEG aspect: the largest fitting one of any aspect.
        val wide = BracketCaptureStream.sizes(listOf(PixelSize(3840, 2160), PixelSize(1920, 1080)), jpeg, preview)
        assertEquals(PixelSize(3840, 2160), wide?.jpeg)
        assertEquals(PixelSize(1920, 1080), wide?.preview)
        assertNull("no YUV output", BracketCaptureStream.sizes(emptyList(), jpeg, preview))
        assertEquals(PixelSize(4000, 3000), BracketCaptureStream.sizes(yuv, emptyList(), preview)?.jpeg)
    }
}
