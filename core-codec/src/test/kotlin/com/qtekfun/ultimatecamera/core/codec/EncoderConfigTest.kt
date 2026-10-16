package com.qtekfun.ultimatecamera.core.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EncoderConfigTest {
    @Test
    fun rejectsTenBitH264() {
        assertThrows(IllegalArgumentException::class.java) { EncoderConfig(VideoCodec.H264, 1920, 1080, 30, 20_000_000, tenBit = true) }
    }

    @Test
    fun labelIsReadable() {
        assertEquals("HEVC 3840x2160@60 10-bit 100Mbps", EncoderConfig(VideoCodec.HEVC, 3840, 2160, 60, 100_000_000, tenBit = true).label)
    }

    @Test
    fun pickPrefersHardwareTenBitEncoders() {
        fun info(name: String, hw: Boolean, tenBit: Boolean) = EncoderInfo(
            name, VideoCodec.HEVC, hw, hw, tenBit, emptyList(), 3840, 2160, listOf(1, 2), mapOf("3840x2160@60" to true), emptyList(), emptyList()
        )
        val list = listOf(info("sw", hw = false, tenBit = true), info("hw8", hw = true, tenBit = false), info("hw10", hw = true, tenBit = true))
        val tenBit = EncoderConfig(VideoCodec.HEVC, 3840, 2160, 60, 1, tenBit = true)
        assertEquals("hw10", CodecCapabilityProbe.pick(list, tenBit)?.name)
        assertEquals("hw8", CodecCapabilityProbe.pick(list.take(2), tenBit.copy(tenBit = false))?.name)
    }
}
