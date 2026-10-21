package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedCamera
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedMode
import com.qtekfun.ultimatecamera.core.capabilities.CameraCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.CodecType
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DeviceIdentity
import com.qtekfun.ultimatecamera.core.capabilities.DirectMode
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange
import com.qtekfun.ultimatecamera.core.capabilities.LutMode
import com.qtekfun.ultimatecamera.core.capabilities.ProbeOutcome
import com.qtekfun.ultimatecamera.core.capabilities.ProbeResult
import com.qtekfun.ultimatecamera.core.capabilities.StopReason
import com.qtekfun.ultimatecamera.core.capabilities.VideoConfig
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbedLadderTest {
    private val fallback = ProfileLadder.profiles()

    @Test
    fun withoutResultsTheFixedLadderIsUsed() {
        assertEquals(fallback, ProbedLadder.ladder(null, "0", VideoCodec.HEVC, fallback))
        assertEquals(fallback, ProbedLadder.ladder(capabilities(emptyList()), "0", VideoCodec.HEVC, fallback))
    }

    @Test
    fun pixel8LikeResultsGiveTheHeldModesBestFirst() {
        // 4K60 10-bit refused by the HAL, 4K30 10-bit held: the ladder starts at 4K30 10-bit, 4K60 10-bit is not offered.
        val results = listOf(
            result(1920, 1080, 30, tenBit = false, ProbeOutcome.IMPLIED_HELD),
            result(1920, 1080, 60, tenBit = false, ProbeOutcome.IMPLIED_HELD),
            result(3840, 2160, 60, tenBit = false, ProbeOutcome.ACCEPTED_AND_HELD),
            result(3840, 2160, 30, tenBit = true, ProbeOutcome.ACCEPTED_AND_HELD),
            result(3840, 2160, 60, tenBit = true, ProbeOutcome.SESSION_REJECTED)
        )
        val ladder = ProbedLadder.ladder(capabilities(results), "0", VideoCodec.HEVC, fallback)
        assertEquals(RecordingProfile(3840, 2160, 30, VideoCodec.HEVC, tenBit = true), ladder.first())
        assertTrue(RecordingProfile(3840, 2160, 60, VideoCodec.HEVC, tenBit = true) !in ladder)
        assertEquals(listOf("4K30 10-bit", "4K60", "1080p60", "1080p30"), ladder.map { it.label })
    }

    @Test
    fun oppoLikeUnadvertised4k60TenBitIsOffered() {
        val results = listOf(result(3840, 2160, 60, tenBit = true, ProbeOutcome.ACCEPTED_AND_HELD))
        assertEquals("4K60 10-bit", ProbedLadder.ladder(capabilities(results), "0", VideoCodec.HEVC, fallback).first().label)
    }

    @Test
    fun flatRecordingIsUsedWhenOnlyTheNoLutVariantHeld() {
        val results = listOf(
            result(3840, 2160, 30, tenBit = true, ProbeOutcome.DROPS),
            result(3840, 2160, 30, tenBit = true, ProbeOutcome.ACCEPTED_AND_HELD, LutMode.NONE)
        )
        assertEquals(listOf("4K30 10-bit"), ProbedLadder.profiles(capabilities(results), "0").map { it.label })
    }

    @Test
    fun anotherCodecUsesItsFixedLadder() {
        val results = listOf(result(3840, 2160, 60, tenBit = true, ProbeOutcome.ACCEPTED_AND_HELD))
        val h264 = ProfileLadder.profiles(codec = VideoCodec.H264)
        assertEquals(h264, ProbedLadder.ladder(capabilities(results), "0", VideoCodec.H264, h264))
    }

    @Test
    fun otherCamerasResultsDoNotLeak() {
        val results = listOf(result(3840, 2160, 60, tenBit = true, ProbeOutcome.ACCEPTED_AND_HELD))
        assertEquals(fallback, ProbedLadder.ladder(capabilities(results), "1", VideoCodec.HEVC, fallback))
    }

    @Test
    fun directEightKIsOfferedOnlyWithExperimentalAndOnlyWhenItHeld() {
        // Pixel 8 expectation (D71): 8K30 direct held, 8K60 direct refused.
        val results = listOf(
            result(3840, 2160, 30, tenBit = true, ProbeOutcome.ACCEPTED_AND_HELD),
            result(7680, 4320, 30, tenBit = true, ProbeOutcome.ACCEPTED_AND_HELD, LutMode.NONE).let {
                it.copy(config = it.config.copy(vendorMode = DirectMode.MODE))
            },
            result(7680, 4320, 60, tenBit = true, ProbeOutcome.SESSION_REJECTED, LutMode.NONE).let {
                it.copy(config = it.config.copy(vendorMode = DirectMode.MODE))
            }
        )
        val caps = capabilities(results)
        assertTrue(ProbedLadder.ladder(caps, "0", VideoCodec.HEVC, fallback).none { it.height == 4320 })
        val experimental = ProbedLadder.ladder(caps, "0", VideoCodec.HEVC, fallback, experimental = true)
        assertEquals(RecordingProfile(7680, 4320, 30, VideoCodec.HEVC, tenBit = true, direct = true), experimental.first())
        assertTrue(experimental.first().isDirect)
        assertTrue(experimental.none { it.height == 4320 && it.fps == 60 })
        // The camera never falls down the ladder into 8K on its own.
        assertTrue(ProfileLadder.isExperimental(experimental.first()))
    }

    @Test
    fun everyOutcomeAndStopReasonHasText() {
        assertEquals(ProbeOutcome.entries.size, ProbeOutcome.entries.map(::outcomeText).distinct().size)
        assertEquals(StopReason.entries.size, StopReason.entries.map(::stopText).distinct().size)
    }

    @Test
    fun marginalDropsHaveTheirOwnTextAndTheLargestGapShowsOnlyForGaps() {
        val config = VideoConfig(1920, 1080, 60, CodecType.HEVC, DynamicRange.STANDARD, LutMode.HARDWARE)
        val marginal = ProbeRow(
            ProbeResult("0", config, true, ProbeOutcome.DROPS, measuredFps = 59.8, droppedFrames = 1, marginal = true, gapCount = 1, maxGapMs = 33.4)
        )
        assertEquals(R.string.autoconfig_outcome_drops_marginal, outcomeText(marginal))
        assertEquals(33.4, marginal.shownGapMs!!, 1e-9)
        val clear = marginal.copy(marginal = false)
        assertEquals(R.string.autoconfig_outcome_drops, outcomeText(clear))
        val held =
            ProbeRow(ProbeResult("0", config, true, ProbeOutcome.ACCEPTED_AND_HELD, measuredFps = 60.0, droppedFrames = 0, gapCount = 0, maxGapMs = 17.1))
        assertEquals(R.string.autoconfig_outcome_held, outcomeText(held))
        assertNull(held.shownGapMs)
        // D103: held over the sustained run with a frame lost now and then says so, with its largest gap.
        val heldWithDrops = marginal.copy(outcome = ProbeOutcome.ACCEPTED_AND_HELD, droppedFrames = 6, maxGapMs = 50.0)
        assertEquals(R.string.autoconfig_outcome_held_marginal, outcomeText(heldWithDrops))
        assertEquals(50.0, heldWithDrops.shownGapMs!!, 1e-9)
    }

    private fun result(w: Int, h: Int, fps: Int, tenBit: Boolean, outcome: ProbeOutcome, lut: LutMode = LutMode.HARDWARE) = ProbeResult(
        cameraId = "0",
        config = VideoConfig(w, h, fps, CodecType.HEVC, if (tenBit) DynamicRange.HLG10 else DynamicRange.STANDARD, lut),
        advertised = true,
        outcome = outcome
    )

    private fun capabilities(results: List<ProbeResult>) = DeviceCapabilities(
        identity = DeviceIdentity("Google", "google", "Pixel 8", "shiba", 36),
        engineVersion = 1,
        cameras = listOf(
            CameraCapabilities(
                AdvertisedCamera("0", "BACK", videoModes = listOf(AdvertisedMode(3840, 2160, 60)), tenBitProfiles = listOf(DynamicRange.HLG10)),
                results
            ),
            CameraCapabilities(AdvertisedCamera("1", "FRONT", videoModes = emptyList()))
        )
    )
}
