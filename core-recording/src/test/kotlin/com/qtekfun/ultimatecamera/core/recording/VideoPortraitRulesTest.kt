package com.qtekfun.ultimatecamera.core.recording

import android.graphics.ImageFormat
import com.qtekfun.ultimatecamera.core.camera.AuxOutput
import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.camera.DepthSupport
import com.qtekfun.ultimatecamera.core.camera.LensOptics
import com.qtekfun.ultimatecamera.core.camera.PixelSize
import com.qtekfun.ultimatecamera.core.camera.StereoPair
import com.qtekfun.ultimatecamera.core.camera.StereoRate
import com.qtekfun.ultimatecamera.core.camera.VideoDepthRates
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurCause
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eligibility of portrait video (D89) on fake phones, the depth cadence, status and notices, depth smoothing. */
class VideoPortraitRulesTest {
    private val ms = 1_000_000L
    private val depthSize = PixelSize(320, 180)
    private val pair = StereoPair("2", "3", LensOptics(6.9f, 9.8f, 7.4f), LensOptics(2.2f, 5.6f, 4.2f))

    /** A phone with a ToF DEPTH16 output that runs at 30 fps (33 ms). */
    private val tof = DepthSupport(depth16 = listOf(depthSize))
    private val tofRates = VideoDepthRates(depth16 = mapOf(depthSize to 33 * ms))

    /** A logical camera with a stereo pair whose 640 x 360 YUV runs at 30 fps. */
    private val stereo = DepthSupport(stereo = pair)
    private val stereoRates = VideoDepthRates(stereo = StereoRate(PixelSize(640, 360), 33 * ms))

    private val on = VideoPortraitOptions(enabled = true, experimental = false, on = true)
    private val p1080p30 = RecordingProfile(1920, 1080, 30, tenBit = false)

    private fun verdict(
        options: VideoPortraitOptions = on,
        profile: RecordingProfile = p1080p30,
        support: DepthSupport = tof,
        rates: VideoDepthRates = tofRates,
        modest: Boolean = false,
        failed: Set<DepthSource> = emptySet()
    ): VideoPortraitVerdict {
        val offered = VideoPortraitRules.sources(support, options.experimental)
        return VideoPortraitRules.verdict(options, profile, offered, offered - failed, support, rates, modest)
    }

    @Test
    fun aToFPhoneRunsWithItsDepthOutput() {
        val run = verdict() as VideoPortraitVerdict.Run
        assertEquals(DepthSource.DEPTH_CAMERA, run.source)
        assertEquals(listOf(AuxOutput(depthSize, ImageFormat.DEPTH16, null)), run.outputs)
        assertEquals(2, run.depthEvery)
        assertEquals(VideoBlurLevel.HIGH, run.start)
    }

    @Test
    fun withTheSettingOffNothingIsOfferedAndWithTheChipOffItIsOnlyOffered() {
        assertEquals(VideoPortraitVerdict.NotOffered, verdict(options = on.copy(enabled = false)))
        assertEquals(VideoPortraitVerdict.Off, verdict(options = on.copy(on = false)))
        // No depth source at all: no chip, never a fake blur.
        assertEquals(VideoPortraitVerdict.NotOffered, verdict(support = DepthSupport(), rates = VideoDepthRates()))
    }

    @Test
    fun theMakersPortraitHasNoVideoAndStereoNeedsExperimental() {
        assertEquals(VideoPortraitVerdict.NotOffered, verdict(support = DepthSupport(bokehExtension = true), rates = VideoDepthRates()))
        assertEquals(VideoPortraitVerdict.NotOffered, verdict(support = stereo, rates = stereoRates))
        val run = verdict(options = on.copy(experimental = true), support = stereo, rates = stereoRates) as VideoPortraitVerdict.Run
        assertEquals(DepthSource.STEREO, run.source)
        assertEquals(listOf("2", "3"), run.outputs.map { it.physicalId })
    }

    @Test
    fun tenBitDirectFastAndHugeModesAreBlocked() {
        assertEquals(VideoPortraitVerdict.Blocked(VideoPortraitBlock.TEN_BIT), verdict(profile = p1080p30.copy(tenBit = true)))
        assertEquals(VideoPortraitVerdict.Blocked(VideoPortraitBlock.DIRECT), verdict(profile = p1080p30.copy(direct = true)))
        assertEquals(VideoPortraitVerdict.Blocked(VideoPortraitBlock.DIRECT), verdict(profile = RecordingProfile(7680, 4320, 30, tenBit = false)))
        assertEquals(VideoPortraitVerdict.Blocked(VideoPortraitBlock.FRAME_RATE), verdict(profile = p1080p30.copy(fps = 120)))
        assertEquals(VideoPortraitVerdict.Blocked(VideoPortraitBlock.FRAME_RATE), verdict(profile = p1080p30.copy(fps = 60), modest = true))
        assertEquals(VideoPortraitVerdict.Blocked(VideoPortraitBlock.SIZE), verdict(profile = RecordingProfile(4096, 2304, 30, tenBit = false)))
    }

    @Test
    fun depthTooSlowForTheFrameRateIsBlockedNotStretched() {
        // 60 fps on a capable phone is allowed, but the 33 ms depth output would stretch every depth request.
        assertEquals(VideoPortraitVerdict.Blocked(VideoPortraitBlock.DEPTH_TOO_SLOW), verdict(profile = p1080p30.copy(fps = 60)))
        val fast = VideoDepthRates(depth16 = mapOf(depthSize to 16 * ms))
        val run = verdict(profile = p1080p30.copy(fps = 60), rates = fast) as VideoPortraitVerdict.Run
        assertEquals(4, run.depthEvery)
        assertEquals(VideoBlurLevel.MEDIUM, run.start)
    }

    @Test
    fun aSourceThatFailedOnThisCameraIsNotTriedAgain() {
        assertEquals(VideoPortraitVerdict.Blocked(VideoPortraitBlock.SESSION_REFUSED), verdict(failed = setOf(DepthSource.DEPTH_CAMERA)))
        // A phone with both falls back to the other source.
        val both = DepthSupport(depth16 = listOf(depthSize), stereo = pair)
        val rates = VideoDepthRates(depth16 = tofRates.depth16, stereo = stereoRates.stereo)
        val run = verdict(options = on.copy(experimental = true), support = both, rates = rates, failed = setOf(DepthSource.DEPTH_CAMERA))
        assertEquals(DepthSource.STEREO, (run as VideoPortraitVerdict.Run).source)
    }

    @Test
    fun modestPhonesGetLessOfEverything() {
        val run = verdict(modest = true) as VideoPortraitVerdict.Run
        assertEquals(3, run.depthEvery)
        assertEquals(VideoBlurLevel.LOW, run.start)
        assertEquals(30, VideoPortraitRules.maxFps(modest = true))
        assertEquals(60, VideoPortraitRules.maxFps(modest = false))
    }

    @Test
    fun theDepthCadenceAimsAtAFewUpdatesASecondAndTheGateSitsJustUnderIt() {
        assertEquals(1, VideoPortraitRules.depthEvery(10, modest = false))
        assertEquals(2, VideoPortraitRules.depthEvery(24, modest = false))
        assertEquals(2, VideoPortraitRules.depthEvery(25, modest = false))
        assertEquals(4, VideoPortraitRules.depthEvery(60, modest = false))
        assertEquals(3, VideoPortraitRules.depthEvery(25, modest = true))
        assertEquals(1, VideoPortraitRules.depthEvery(0, modest = false))
        val interval = VideoPortraitRules.minDepthIntervalNanos(30, 2)
        assertEquals(2 * (1_000_000_000L / 30) * 80 / 100, interval)
        assertTrue(interval < 2 * (1_000_000_000L / 30))
    }

    @Test
    fun theStatusFollowsTheVerdict() {
        assertEquals(VideoPortraitStatus(), VideoPortraitRules.status(VideoPortraitVerdict.NotOffered, on = true))
        assertEquals(VideoPortraitStatus(offered = true), VideoPortraitRules.status(VideoPortraitVerdict.Off, on = false))
        val blocked = VideoPortraitRules.status(VideoPortraitVerdict.Blocked(VideoPortraitBlock.TEN_BIT), on = true)
        assertEquals(VideoPortraitStatus(offered = true, on = true, block = VideoPortraitBlock.TEN_BIT), blocked)
        val running = VideoPortraitRules.status(verdict(), on = true)
        assertEquals(DepthSource.DEPTH_CAMERA, running.source)
        assertEquals(VideoBlurLevel.HIGH, running.level)
        assertFalse(running.lowered)
    }

    @Test
    fun aStepDownLowersTheStatusAndTellsTheUser() {
        val running = VideoPortraitRules.status(verdict(), on = true)
        val lower = VideoPortraitRules.afterChange(running, VideoBlurLevel.MEDIUM, VideoBlurCause.SLOW_FRAMES)
        assertTrue(lower.lowered)
        assertEquals(VideoPortraitNotice.Lowered(VideoBlurLevel.MEDIUM), VideoPortraitRules.notice(VideoBlurLevel.MEDIUM, VideoBlurCause.DROPPED_FRAMES))
        val off = VideoPortraitRules.afterChange(lower, VideoBlurLevel.OFF, VideoBlurCause.DROPPED_FRAMES)
        assertEquals(null, off.source)
        assertEquals(VideoPortraitBlock.TOO_SLOW, off.block)
        assertFalse(off.lowered)
        assertEquals(VideoPortraitNotice.TurnedOff(VideoPortraitBlock.TOO_SLOW), VideoPortraitRules.notice(VideoBlurLevel.OFF, VideoBlurCause.SLOW_FRAMES))
        assertEquals(VideoPortraitNotice.TurnedOff(VideoPortraitBlock.GPU_FAILED), VideoPortraitRules.notice(VideoBlurLevel.OFF, VideoBlurCause.GPU_FAILURE))
        assertEquals(VideoPortraitBlock.GPU_FAILED, VideoPortraitRules.afterChange(running, VideoBlurLevel.OFF, VideoBlurCause.GPU_FAILURE).block)
    }

    // --- Depth smoothing between the camera and the GPU ---

    /** A subject (2 D) on the left half, the background (0.2 D) on the right. */
    private fun scene(subject: Float = 2f, background: Float = 0.2f) = Plane(20, 10, FloatArray(200) { i -> if (i % 20 < 10) subject else background })

    @Test
    fun theFocusIsTheTappedPointOnTheSmoothedMapAndTheApertureIsPassedOn() {
        val smoother = VideoDepthSmoother()
        val left = PortraitLook(fNumber = 2.8f, focusX = 0.2f, focusY = 0.5f)
        val first = smoother.next(scene(), left, newPoint = true)
        assertEquals(2f, first.focus, 0f)
        assertEquals(2.8f, first.fNumber, 0f)
        // A tap on the background racks towards it over a few updates instead of jumping.
        val right = left.copy(focusX = 0.8f)
        val racking = smoother.next(scene(), right, newPoint = true)
        assertTrue("focus ${racking.focus}", racking.focus < 2f && racking.focus > 0.2f)
        var focus = racking.focus
        repeat(10) { focus = smoother.next(scene(), right, newPoint = false).focus }
        assertEquals(0.2f, focus, 0.05f)
    }

    @Test
    fun noisyDepthIsSmoothedBeforeItReachesTheRenderer() {
        val smoother = VideoDepthSmoother()
        val look = PortraitLook(fNumber = 2.8f)
        smoother.next(scene(), look, newPoint = false)
        val noisy = smoother.next(scene(background = 0.4f), look, newPoint = false)
        // The background moved by 0.2 D (noise): it follows only part of the way.
        assertTrue(noisy.inverse[15, 5] < 0.4f && noisy.inverse[15, 5] > 0.2f)
        smoother.reset()
        assertEquals(0.4f, smoother.next(scene(background = 0.4f), look, newPoint = false).inverse[15, 5], 0f)
    }

    @Test
    fun noDepthAtThePointKeepsNoFocusRatherThanAFakeOne() {
        val smoother = VideoDepthSmoother()
        val empty = Plane(20, 10).apply { data.fill(Bokeh.INVALID) }
        assertEquals(Bokeh.INVALID, smoother.next(empty, PortraitLook(fNumber = 2.8f), newPoint = false).focus, 0f)
    }
}
