package com.qtekfun.ultimatecamera.core.camera

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which depth outputs a video session may use (D89): only those that keep up with the take's frame rate. */
class VideoDepthSelectorTest {
    private val video = PixelSize(1920, 1080)
    private val pair = StereoPair("2", "3", LensOptics(6.9f, 9.8f, 7.4f), LensOptics(2.2f, 5.6f, 4.2f))
    private val ms = 1_000_000L

    @Test
    fun anOutputKeepsUpOnlyWhenItsReportedDurationFitsTheFrameInterval() {
        assertTrue(VideoDepthSelector.keepsUp(33 * ms, 30))
        assertFalse(VideoDepthSelector.keepsUp(34 * ms, 30))
        assertTrue(VideoDepthSelector.keepsUp(16 * ms, 60))
        assertFalse(VideoDepthSelector.keepsUp(33 * ms, 60))
        // An unreported duration is not taken as fast.
        assertFalse(VideoDepthSelector.keepsUp(0, 30))
        assertFalse(VideoDepthSelector.keepsUp(null, 30))
        assertEquals(1_000_000_000L / 30, VideoDepthSelector.frameNanos(30))
        assertEquals(1_000_000_000L, VideoDepthSelector.frameNanos(0))
    }

    @Test
    fun depth16TakesTheFastSizeClosestToTheVideoAspect() {
        val wide = PixelSize(320, 180)
        val square = PixelSize(240, 240)
        val big = PixelSize(640, 360)
        val support = DepthSupport(depth16 = listOf(wide, square, big))
        val rates = VideoDepthRates(depth16 = mapOf(wide to 33 * ms, square to 16 * ms, big to 66 * ms))
        // At 30 fps the 640 x 360 is too slow: the 320 x 180 has the video's aspect.
        assertEquals(listOf(AuxOutput(wide, ImageFormat.DEPTH16, null)), VideoDepthSelector.outputs(DepthSource.DEPTH_CAMERA, support, rates, video, 30))
        // At 60 fps only the square one keeps up.
        assertEquals(listOf(AuxOutput(square, ImageFormat.DEPTH16, null)), VideoDepthSelector.outputs(DepthSource.DEPTH_CAMERA, support, rates, video, 60))
        assertNull(VideoDepthSelector.outputs(DepthSource.DEPTH_CAMERA, support, rates, video, 120))
        assertNull(VideoDepthSelector.outputs(DepthSource.DEPTH_CAMERA, support, VideoDepthRates(), video, 30))
    }

    @Test
    fun stereoNeedsThePairAndASharedSizeFastEnough() {
        val support = DepthSupport(stereo = pair)
        val size = PixelSize(640, 360)
        val rates = VideoDepthRates(stereo = StereoRate(size, 33 * ms))
        assertEquals(
            listOf(AuxOutput(size, ImageFormat.YUV_420_888, "2"), AuxOutput(size, ImageFormat.YUV_420_888, "3")),
            VideoDepthSelector.outputs(DepthSource.STEREO, support, rates, video, 30)
        )
        assertNull(VideoDepthSelector.outputs(DepthSource.STEREO, support, rates, video, 60))
        assertNull(VideoDepthSelector.outputs(DepthSource.STEREO, support, VideoDepthRates(), video, 30))
        assertNull(VideoDepthSelector.outputs(DepthSource.STEREO, DepthSupport(), rates, video, 30))
    }

    @Test
    fun theMakersExtensionHasNoVideo() {
        assertNull(VideoDepthSelector.outputs(DepthSource.VENDOR_BOKEH, DepthSupport(bokehExtension = true), VideoDepthRates(), video, 30))
    }
}
