package com.qtekfun.ultimatecamera.core.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecProbeDecisionsTest {
    @Test
    fun codecsAreMatchedByMimeIgnoringCase() {
        assertEquals(listOf(VideoCodec.HEVC), CodecCapabilityProbe.codecsFor(listOf("VIDEO/HEVC")))
        assertEquals(
            listOf(VideoCodec.H264, VideoCodec.AV1),
            CodecCapabilityProbe.codecsFor(listOf("video/av01", "video/avc", "audio/mp4a-latm"))
        )
        assertTrue(CodecCapabilityProbe.codecsFor(listOf("video/x-vnd.on2.vp9")).isEmpty())
    }

    @Test
    fun tenBitNeedsAMain10Profile() {
        assertTrue(CodecCapabilityProbe.tenBitProfile(VideoCodec.HEVC, listOf(CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCProfileMain10)))
        assertTrue(CodecCapabilityProbe.tenBitProfile(VideoCodec.HEVC, listOf(CodecProfileLevel.HEVCProfileMain10HDR10)))
        assertFalse(CodecCapabilityProbe.tenBitProfile(VideoCodec.HEVC, listOf(CodecProfileLevel.HEVCProfileMain)))
        assertTrue(CodecCapabilityProbe.tenBitProfile(VideoCodec.AV1, listOf(CodecProfileLevel.AV1ProfileMain10)))
        assertTrue(CodecCapabilityProbe.tenBitProfile(VideoCodec.AV1, listOf(CodecProfileLevel.AV1ProfileMain10HDR10)))
        assertFalse(CodecCapabilityProbe.tenBitProfile(VideoCodec.AV1, listOf(CodecProfileLevel.AV1ProfileMain8)))
        // H.264 High 10 exists, but the pipeline never records 10-bit AVC.
        assertFalse(CodecCapabilityProbe.tenBitProfile(VideoCodec.H264, listOf(CodecProfileLevel.AVCProfileHigh10)))
    }

    @Test
    fun bitrateModesInAFixedOrder() {
        val all = setOf(
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        )
        assertEquals(listOf("CBR", "VBR", "CQ"), CodecCapabilityProbe.bitrateModes { it in all })
        assertEquals(listOf("VBR"), CodecCapabilityProbe.bitrateModes { it == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR })
        assertTrue(CodecCapabilityProbe.bitrateModes { false }.isEmpty())
    }

    @Test
    fun supportsChecksTheCatalogueModes() {
        val asked = mutableListOf<String>()
        val supports = CodecCapabilityProbe.supports { w, h, fps ->
            asked += "${w}x$h@$fps"
            h <= 1080 || fps <= 60
        }
        val expected = listOf("1920x1080@30", "1920x1080@60", "1920x1080@120", "3840x2160@30", "3840x2160@60", "3840x2160@120")
        assertEquals(expected, asked)
        assertEquals(expected, supports.keys.toList())
        assertEquals(false, supports["3840x2160@120"])
        assertEquals(true, supports["1920x1080@120"])
    }

    @Test
    fun encodedStatsCountOnlyPictureBuffers() {
        assertFalse("codec config", EncodedStats.isFrame(30, MediaCodec.BUFFER_FLAG_CODEC_CONFIG))
        assertFalse("empty", EncodedStats.isFrame(0, 0))
        assertTrue(EncodedStats.isFrame(1000, MediaCodec.BUFFER_FLAG_KEY_FRAME))
        assertTrue("the last frame can carry EOS", EncodedStats.isFrame(1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM))
        assertTrue(EncodedStats.isEndOfStream(MediaCodec.BUFFER_FLAG_END_OF_STREAM or MediaCodec.BUFFER_FLAG_KEY_FRAME))
        assertFalse(EncodedStats.isEndOfStream(MediaCodec.BUFFER_FLAG_KEY_FRAME))

        val stats = EncodedStats()
        assertEquals(-1L, stats.firstPtsUs.get())
        stats.count(1000, 33_000)
        stats.count(500, 66_000)
        assertEquals(2, stats.frames.get())
        assertEquals(1500L, stats.bytes.get())
        assertEquals(33_000L, stats.firstPtsUs.get())
        assertEquals(66_000L, stats.lastPtsUs.get())
    }
}
