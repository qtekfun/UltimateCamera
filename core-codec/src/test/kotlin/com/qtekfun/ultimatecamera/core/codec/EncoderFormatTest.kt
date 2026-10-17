package com.qtekfun.ultimatecamera.core.codec

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderFormatTest {
    private val hevc10 = EncoderConfig(VideoCodec.HEVC, 3840, 2160, 60, 100_000_000, tenBit = true, iFrameIntervalSeconds = 2)

    @Test
    fun surfaceInputRealtimeAndTheRealRate() {
        val keys = encoderFormatKeys(hevc10)
        assertEquals(CodecCapabilities.COLOR_FormatSurface, keys[MediaFormat.KEY_COLOR_FORMAT])
        assertEquals(100_000_000, keys[MediaFormat.KEY_BIT_RATE])
        assertEquals(60, keys[MediaFormat.KEY_FRAME_RATE])
        assertEquals(60, keys[MediaFormat.KEY_OPERATING_RATE])
        assertEquals(2, keys[MediaFormat.KEY_I_FRAME_INTERVAL])
        assertEquals(0, keys[MediaFormat.KEY_PRIORITY])
    }

    @Test
    fun tenBitDefaultsToMain10WithHlgBt2020Limited() {
        val keys = encoderFormatKeys(hevc10)
        assertEquals(CodecProfileLevel.HEVCProfileMain10, keys[MediaFormat.KEY_PROFILE])
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, keys[MediaFormat.KEY_COLOR_STANDARD])
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, keys[MediaFormat.KEY_COLOR_TRANSFER])
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, keys[MediaFormat.KEY_COLOR_RANGE])
    }

    @Test
    fun av1TenBitUsesTheAv1Main10Profile() {
        val keys = encoderFormatKeys(hevc10.copy(codec = VideoCodec.AV1))
        assertEquals(CodecProfileLevel.AV1ProfileMain10, keys[MediaFormat.KEY_PROFILE])
    }

    @Test
    fun ucLogKeepsBt2020WithAnSdrTransfer() {
        val keys = encoderFormatKeys(hevc10.copy(colorTag = ColorTag.SDR_BT2020))
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, keys[MediaFormat.KEY_COLOR_STANDARD])
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, keys[MediaFormat.KEY_COLOR_TRANSFER])
    }

    @Test
    fun bakedLutIsTaggedRec709() {
        val keys = encoderFormatKeys(EncoderConfig(VideoCodec.H264, 1920, 1080, 30, 20_000_000, tenBit = false, colorTag = ColorTag.SDR_BT709))
        assertNull(keys[MediaFormat.KEY_PROFILE])
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, keys[MediaFormat.KEY_COLOR_STANDARD])
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, keys[MediaFormat.KEY_COLOR_TRANSFER])
    }

    @Test
    fun eightBitWithoutTagWritesNoColourSignalling() {
        val keys = encoderFormatKeys(EncoderConfig(VideoCodec.H264, 1920, 1080, 30, 20_000_000, tenBit = false))
        listOf(MediaFormat.KEY_PROFILE, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE)
            .forEach { assertFalse(it, it in keys) }
    }

    @Test
    fun pickSkipsOtherCodecsAndPrefersEncodersClaimingTheMode() {
        fun info(name: String, codec: VideoCodec, claims: Boolean) = EncoderInfo(
            name, codec, true, true, false, emptyList(), 3840, 2160, listOf(1, 2), mapOf("1920x1080@120" to claims), emptyList(), emptyList()
        )
        val list = listOf(info("avc", VideoCodec.H264, claims = true), info("hevc-no", VideoCodec.HEVC, false), info("hevc-yes", VideoCodec.HEVC, true))
        val config = EncoderConfig(VideoCodec.HEVC, 1920, 1080, 120, 1, tenBit = false)
        assertEquals("hevc-yes", CodecCapabilityProbe.pick(list, config)?.name)
        assertNull(CodecCapabilityProbe.pick(list, config.copy(codec = VideoCodec.AV1)))
        assertNull("no 10-bit encoder", CodecCapabilityProbe.pick(list, config.copy(tenBit = true)))
    }
}
