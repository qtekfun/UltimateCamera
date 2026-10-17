package com.qtekfun.ultimatecamera.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLadderTest {
    private val ladder = ProfileLadder.profiles(experimental = true)

    @Test
    fun startsAt4k60TenBitAndKeeps4k30TenBitAsTheNextStep() {
        assertEquals("4K60 10-bit", ladder[0].label)
        assertEquals("4K30 10-bit", ProfileLadder.next(ladder[0], emptySet(), ladder)?.label)
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
}
