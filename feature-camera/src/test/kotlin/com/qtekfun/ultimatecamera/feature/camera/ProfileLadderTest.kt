package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLadderTest {
    private val ladder = ProfileLadder.profiles(experimental = true)

    @Test
    fun startsAt4k60TenBitAndKeeps60fpsIn8BitAsTheNextStep() {
        // D105: a camera that refuses 4K60 10-bit records 4K60 8-bit before 4K30 10-bit.
        assertEquals("4K60 10-bit", ladder[0].label)
        assertEquals("4K60", ProfileLadder.next(ladder[0], emptySet(), ladder)?.label)
        assertEquals("4K30 10-bit", ProfileLadder.next(ladder[1], emptySet(), ladder)?.label)
    }

    @Test
    fun skipsRefusedModes() {
        val refused = setOf(ladder[1], ladder[2])
        assertEquals("4K30", ProfileLadder.next(ladder[0], refused, ladder)?.label)
    }

    @Test
    fun neverFallsInto8k() {
        val last = ladder.last { !ProfileLadder.isExperimental(it) }
        assertNull(ProfileLadder.next(last, emptySet(), ladder))
    }

    @Test
    fun eightKOnlyWhenExperimental() {
        assertFalse(ProfileLadder.profiles().any(ProfileLadder::isExperimental))
        assertTrue(ladder.any { it.label == "8K30" })
    }

    @Test
    fun tenBitBitratesAreHigherThanEightBitAtTheSameMode() {
        val tenBit = ladder.first { it.label == "4K60 10-bit" }
        val eightBit = ladder.first { it.label == "4K60" }
        assertTrue(tenBit.bitrateBps > eightBit.bitrateBps)
    }

    @Test
    fun h264IsEightBitOnlyWithMoreBitrateThanHevc() {
        val h264 = ProfileLadder.profiles(experimental = true, codec = VideoCodec.H264)
        assertTrue(h264.none { it.tenBit || ProfileLadder.isExperimental(it) })
        assertEquals("4K60 H264", h264.first().label)
        val hevc = ProfileLadder.profiles().first { it.label == "4K60" }
        assertTrue(h264.first().bitrateBps > hevc.bitrateBps)
    }

    @Test
    fun av1KeepsTenBitAndUsesLessBitrate() {
        val av1 = ProfileLadder.profiles(codec = VideoCodec.AV1)
        assertEquals("4K60 10-bit AV1", av1.first().label)
        assertTrue(av1.first().bitrateBps < ProfileLadder.profiles().first().bitrateBps)
    }
}
