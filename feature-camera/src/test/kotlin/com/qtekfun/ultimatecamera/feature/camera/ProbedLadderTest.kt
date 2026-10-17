package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedCamera
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedMode
import com.qtekfun.ultimatecamera.core.capabilities.CameraCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.CodecType
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DeviceIdentity
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange
import com.qtekfun.ultimatecamera.core.capabilities.LutMode
import com.qtekfun.ultimatecamera.core.capabilities.ProbeOutcome
import com.qtekfun.ultimatecamera.core.capabilities.ProbeResult
import com.qtekfun.ultimatecamera.core.capabilities.StopReason
import com.qtekfun.ultimatecamera.core.capabilities.VideoConfig
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbedLadderTest {
    private val fallback = ProfileLadder.profiles()

    @Test
    fun withoutResultsTheFixedLadderIsUsed() {
        assertEquals(fallback, ProbedLadder.ladder(null, "0", fallback))
        assertEquals(fallback, ProbedLadder.ladder(capabilities(emptyList()), "0", fallback))
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
        val ladder = ProbedLadder.ladder(capabilities(results), "0", fallback)
        assertEquals(RecordingProfile(3840, 2160, 30, VideoCodec.HEVC, tenBit = true), ladder.first())
        assertTrue(RecordingProfile(3840, 2160, 60, VideoCodec.HEVC, tenBit = true) !in ladder)
        assertEquals(listOf("4K30 10-bit", "4K60", "1080p60", "1080p30"), ladder.map { it.label })
    }

    @Test
    fun oppoLikeUnadvertised4k60TenBitIsOffered() {
        val results = listOf(result(3840, 2160, 60, tenBit = true, ProbeOutcome.ACCEPTED_AND_HELD))
        assertEquals("4K60 10-bit", ProbedLadder.ladder(capabilities(results), "0", fallback).first().label)
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
    fun otherCamerasResultsDoNotLeak() {
        val results = listOf(result(3840, 2160, 60, tenBit = true, ProbeOutcome.ACCEPTED_AND_HELD))
        assertEquals(fallback, ProbedLadder.ladder(capabilities(results), "1", fallback))
    }

    @Test
    fun everyOutcomeAndStopReasonHasText() {
        assertEquals(ProbeOutcome.entries.size, ProbeOutcome.entries.map(::outcomeText).distinct().size)
        assertEquals(StopReason.entries.size, StopReason.entries.map(::stopText).distinct().size)
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
