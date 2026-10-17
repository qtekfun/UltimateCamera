package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.capabilities.CodecType
import com.qtekfun.ultimatecamera.core.capabilities.DirectMode
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange
import com.qtekfun.ultimatecamera.core.capabilities.LutMode
import com.qtekfun.ultimatecamera.core.capabilities.VideoConfig
import com.qtekfun.ultimatecamera.core.codec.ColorTag
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.recording.probe.ProbeMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which takes skip the GL pass (D71) and what they record. */
class DirectProfileTest {
    @Test
    fun eightKIsAlwaysDirectAndOtherProfilesOnlyWhenFlagged() {
        assertTrue(RecordingProfile(7680, 4320, 30).isDirect)
        assertTrue(RecordingProfile(7680, 4320, 60, tenBit = false).isDirect)
        assertFalse(RecordingProfile(3840, 2160, 60).isDirect)
        assertTrue(RecordingProfile(3840, 2160, 60, direct = true).isDirect)
    }

    @Test
    fun aDirectTakeIsTaggedAsTheCameraDeliversAndPreviewsSmall() {
        val eightK = RecordingProfile(7680, 4320, 30, VideoCodec.HEVC, tenBit = true)
        assertEquals(ColorTag.HLG_BT2020, DirectPath.colorTag(eightK))
        assertNull(DirectPath.colorTag(eightK.copy(tenBit = false)))
        assertEquals(1920 to 1080, DirectPath.previewSize(eightK))
    }

    @Test
    fun probedDirectVariantsBecomeDirectProfiles() {
        val held = VideoConfig(7680, 4320, 30, CodecType.HEVC, DynamicRange.HLG10, LutMode.NONE, DirectMode.MODE)
        val profile = ProbeMapping.profile(held)
        assertTrue(profile.direct)
        assertEquals(RecordingProfile(7680, 4320, 30, VideoCodec.HEVC, tenBit = true, direct = true), profile)
        assertFalse(ProbeMapping.profile(held.copy(width = 3840, height = 2160, vendorMode = null)).direct)
    }
}
