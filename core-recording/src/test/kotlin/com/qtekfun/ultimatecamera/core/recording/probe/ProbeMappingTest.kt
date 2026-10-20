package com.qtekfun.ultimatecamera.core.recording.probe

import com.qtekfun.ultimatecamera.core.camera.CameraInfo
import com.qtekfun.ultimatecamera.core.camera.StreamMode
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedMode
import com.qtekfun.ultimatecamera.core.capabilities.CodecType
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange
import com.qtekfun.ultimatecamera.core.capabilities.LutMode
import com.qtekfun.ultimatecamera.core.capabilities.VideoConfig
import com.qtekfun.ultimatecamera.core.codec.EncoderInfo
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeMappingTest {
    private val uhd60TenBit = VideoConfig(3840, 2160, 60, CodecType.HEVC, DynamicRange.HLG10, LutMode.HARDWARE)

    @Test
    fun codecsMapBothWays() {
        CodecType.entries.forEach { assertEquals(it, ProbeMapping.codecType(ProbeMapping.codec(it))) }
        VideoCodec.entries.forEach { assertEquals(it, ProbeMapping.codec(ProbeMapping.codecType(it))) }
    }

    @Test
    fun lutModeSelectsTheInterpolationOrNoPass() {
        assertNull(ProbeMapping.interpolation(LutMode.NONE))
        assertEquals(LutInterpolation.HARDWARE, ProbeMapping.interpolation(LutMode.HARDWARE))
        assertEquals(LutInterpolation.MANUAL_TRILINEAR, ProbeMapping.interpolation(LutMode.MANUAL_TRILINEAR))
    }

    @Test
    fun sdrHasNoDynamicRangeProfileAndTenBitRangesDo() {
        assertNull(ProbeMapping.dynamicRangeProfile(DynamicRange.STANDARD))
        val profiles = DynamicRange.entries.filter { it.tenBit }.map { ProbeMapping.dynamicRangeProfile(it) }
        assertEquals(profiles.size, profiles.filterNotNull().distinct().size)
    }

    @Test
    fun probedConfigBecomesTheSameProfileTheFixedLadderUses() {
        assertEquals(RecordingProfile(3840, 2160, 60, VideoCodec.HEVC, tenBit = true), ProbeMapping.profile(uhd60TenBit))
        val eightBit = ProbeMapping.profile(VideoConfig(1920, 1080, 30, CodecType.H264, DynamicRange.STANDARD, LutMode.NONE))
        assertEquals(RecordingProfile(1920, 1080, 30, VideoCodec.H264, tenBit = false), eightBit)
    }

    @Test
    fun encoderIsLoadedAtTheRecordingBitrate() {
        val e = ProbeMapping.encoderConfig(uhd60TenBit)
        assertEquals(VideoCodec.HEVC, e.codec)
        assertEquals(RecordingProfile.defaultBitrate(3840, 2160, 60, true), e.bitrateBps)
        assertEquals(true, e.tenBit)
    }

    @Test
    fun cameraInfoKeepsTenBitProfilesOnlyAndModes() {
        val info = cameraInfo(profiles = listOf("STANDARD", "HLG10", "DOLBY_VISION_10B_HDR_OEM", "HDR10"), recommended = "DOLBY_VISION_10B_HDR_OEM")
        val camera = ProbeMapping.advertisedCamera(info)
        assertEquals(listOf(DynamicRange.HLG10, DynamicRange.HDR10), camera.tenBitProfiles)
        assertNull(camera.recommendedTenBitProfile)
        assertEquals(listOf(AdvertisedMode(3840, 2160, 30), AdvertisedMode(1920, 1080, 60)), camera.videoModes)
        assertEquals(listOf("com.oplus.feature"), camera.vendorRequestKeys)
        assertEquals(DynamicRange.HLG10, camera.tenBitProfile)
    }

    @Test
    fun depthSourcesReachTheCatalogue() {
        val info = cameraInfo(profiles = emptyList(), recommended = null).copy(depthSources = listOf("extension_bokeh", "stereo:2+3"))
        assertEquals(listOf("extension_bokeh", "stereo:2+3"), ProbeMapping.advertisedCamera(info).depthSources)
    }

    @Test
    fun softwareEncodersAreLeftOut() {
        val list = ProbeMapping.advertisedEncoders(listOf(encoder("c2.hw.hevc", hardware = true), encoder("c2.android.hevc", hardware = false)))
        assertEquals(listOf("c2.hw.hevc"), list.map { it.name })
        assertEquals(true, list.single().supports(uhd60TenBit))
    }

    @Test
    fun frameWindowWithoutGapsHasNoDrops() {
        // 240 frames at 60 fps: the span between the timestamps at both ends is 240 frame intervals.
        val w = FrameWindow.of(60, 1_000_000, 300, 1_000_000 + 4_000_000, 60)
        assertEquals(0, w.droppedFrames)
        assertEquals(60.0, w.measuredFps, 0.01)
    }

    @Test
    fun frameWindowCountsASustainedShortfallBeyondDrift() {
        // 110 of 120 frames: 10 short, minus the 1 % drift allowance (2 frames).
        val w = FrameWindow.of(0, 0, 110, 4_000_000, 30)
        assertEquals(8, w.droppedFrames)
        assertEquals(27.5, w.measuredFps, 0.01)
    }

    @Test
    fun aSensorSlightlyBelowItsNominalRateIsNotDropping() {
        // Pixel 8 4K60 8-bit probe: 59.9 fps over 4 s used to report 1 drop.
        assertEquals(0, FrameWindow.of(0, 0, 239, 4_000_000, 60).droppedFrames)
    }

    @Test
    fun aSingleRealGapStillCounts() {
        assertEquals(1, FrameWindow.of(0, 0, 239, 4_000_000, 60, gapDrops = 1).droppedFrames)
    }

    @Test
    fun framesTheCameraSkipsInLowLightAreNotDrops() {
        // 4K60 face down: the camera stretched 4 frames to 33 ms in the window; the pipeline lost none of the rest.
        assertEquals(0, FrameWindow.of(0, 0, 236, 4_000_000, 60, gapDrops = 4, cameraSkips = 4).droppedFrames)
    }

    @Test
    fun pipelineDropsStillCountNextToCameraSkips() {
        // Same scene with a heavy LUT pass: 6 gaps in the encoded frames, 4 of them the camera's, 2 the GPU's.
        assertEquals(2, FrameWindow.of(0, 0, 234, 4_000_000, 60, gapDrops = 6, cameraSkips = 4).droppedFrames)
    }

    @Test
    fun frameWindowToleratesTimestampJitterAndEmptyWindows() {
        assertEquals(0, FrameWindow.of(0, 0, 120, 4_003_000, 30).droppedFrames)
        assertEquals(FrameWindow(0.0, 0), FrameWindow.of(0, -1, 0, -1, 30))
        assertEquals(FrameWindow(0.0, 0), FrameWindow.of(5, 100, 5, 100, 30))
    }

    @Test
    fun vendorIntArraysParse() {
        assertArrayEquals(intArrayOf(1, 2, 60), VendorValues.intArray(" 1, 2 ,60,"))
    }

    private fun encoder(name: String, hardware: Boolean) = EncoderInfo(
        name = name,
        codec = VideoCodec.HEVC,
        hardware = hardware,
        vendor = hardware,
        tenBitProfile = true,
        profiles = emptyList(),
        maxWidth = 4096,
        maxHeight = 2176,
        bitrateRangeBps = listOf(1, 200_000_000),
        supports = mapOf("3840x2160@60" to true),
        performancePoints = emptyList(),
        bitrateModes = listOf("VBR")
    )

    private fun cameraInfo(profiles: List<String>, recommended: String?) = CameraInfo(
        id = "0",
        physicalIds = listOf("2", "3"),
        facing = "BACK",
        hardwareLevel = "LEVEL_3",
        capabilities = emptyList(),
        focalLengthsMm = emptyList(),
        apertures = emptyList(),
        sensorSizeMm = emptyList(),
        pixelArray = emptyList(),
        isoRange = emptyList(),
        exposureRangeNs = emptyList(),
        aeTargetFpsRanges = emptyList(),
        videoStabilizationModes = emptyList(),
        opticalStabilizationModes = emptyList(),
        zoomRatioRange = emptyList(),
        maxDigitalZoom = null,
        minFocusDistanceDiopters = null,
        dynamicRangeProfiles = profiles,
        recommendedTenBitProfile = recommended,
        colorSpaces = emptyList(),
        tonemapModes = emptyList(),
        videoModes = listOf(StreamMode(3840, 2160, 30), StreamMode(1920, 1080, 60)),
        highSpeedModes = emptyList(),
        vendorCharacteristicKeys = emptyList(),
        vendorRequestKeys = listOf("com.oplus.feature"),
        vendorResultKeys = emptyList()
    )

    @Test
    fun measuredSensorDriftIsNotADrop() {
        // Pixel 8 pipeline benchmark: 596 of 600 frames over 20 s (29.8 fps), zero gaps.
        assertEquals(0, FrameWindow.of(0, 0, 596 + 2, 20_000_000, 30).droppedFrames)
        // Pixel Fold probe: 29.7 fps over 4 s.
        assertEquals(0, FrameWindow.of(0, 0, 119, 4_000_000, 30).droppedFrames)
    }

    @Test
    fun gapStatsGiveTheLargestStepInMsAndNoneWithoutTwoFrames() {
        assertEquals(GapStats(1, 33.4), GapStats.of(1, 33_400_000L))
        assertEquals(GapStats(0, null), GapStats.of(0, 0L))
        assertEquals(0, GapStats.of(-1, 16_666_666L).count)
    }
}
