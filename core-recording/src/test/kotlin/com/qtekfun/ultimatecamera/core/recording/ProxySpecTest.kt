package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySpecTest {
    @Test
    fun uhd60GivesA720p30Proxy() {
        val spec = ProxySpec.forMain(3840, 2160, 60, modest = false)
        assertEquals(1280, spec.width)
        assertEquals(720, spec.height)
        assertEquals(30, spec.fps)
        assertTrue(spec.bitrateBps <= ProxySpec.MAX_BITRATE)
    }

    @Test
    fun modestPhonesGet540p() {
        val spec = ProxySpec.forMain(3840, 2160, 30, modest = true)
        assertEquals(960 to 540, spec.width to spec.height)
        assertTrue(spec.bitrateBps in ProxySpec.MIN_BITRATE..ProxySpec.MAX_BITRATE)
    }

    @Test
    fun theAspectRatioIsKeptWithEvenSizes() {
        val spec = ProxySpec.forMain(4000, 3000, 30, modest = false)
        assertEquals(1280 to 960, spec.width to spec.height)
        val odd = ProxySpec.forMain(4032, 2268, 30, modest = false)
        assertEquals(1280, odd.width)
        assertEquals(0, odd.height % 2)
    }

    @Test
    fun aSmallTakeIsNeverUpscaled() {
        val spec = ProxySpec.forMain(1280, 720, 24, modest = false)
        assertEquals(1280 to 720, spec.width to spec.height)
        assertEquals(24, spec.fps)
    }

    @Test
    fun theProxyIsH264EightBit() {
        val config = ProxySpec.forMain(1920, 1080, 30, modest = false).encoderConfig
        assertEquals(VideoCodec.H264, config.codec)
        assertFalse(config.tenBit)
        assertTrue(config.bitrateBps <= 10_000_000)
    }
}
