package com.qtekfun.ultimatecamera.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTierClassifierTest {
    private val gib = 1024L * 1024 * 1024
    private val pixel8 = PhoneTraits(lowRam = false, totalRamBytes = 8 * gib, glRenderer = "Mali-G715", encodes4k30 = true)

    @Test
    fun aFlagshipIsCapable() {
        assertEquals(PhoneTier.CAPABLE, PhoneTierClassifier.classify(pixel8))
        assertEquals(PhoneTier.CAPABLE, PhoneTierClassifier.classify(pixel8.copy(glRenderer = "Adreno (TM) 830")))
    }

    @Test
    fun anUnknownRendererDoesNotMakeAPhoneModest() {
        assertEquals(PhoneTier.CAPABLE, PhoneTierClassifier.classify(pixel8.copy(glRenderer = "")))
    }

    @Test
    fun anySignalOfAModestPhoneWins() {
        assertEquals(PhoneTier.MODEST, PhoneTierClassifier.classify(pixel8.copy(lowRam = true)))
        assertEquals(PhoneTier.MODEST, PhoneTierClassifier.classify(pixel8.copy(totalRamBytes = 4 * gib)))
        assertEquals(PhoneTier.MODEST, PhoneTierClassifier.classify(pixel8.copy(glRenderer = "Mali-G57 MC2")))
        assertEquals(PhoneTier.MODEST, PhoneTierClassifier.classify(pixel8.copy(encodes4k30 = false)))
    }

    @Test
    fun sixGibIsTheLine() {
        assertEquals(PhoneTier.CAPABLE, PhoneTierClassifier.classify(pixel8.copy(totalRamBytes = 6 * gib)))
        assertEquals(PhoneTier.MODEST, PhoneTierClassifier.classify(pixel8.copy(totalRamBytes = 6 * gib - 1)))
    }

    @Test
    fun entryLevelGpusAreRecognisedByTheirRendererString() {
        listOf("Mali-T830", "Mali-G52 MC2", "Mali-G68 MC4", "Adreno (TM) 505", "Adreno (TM) 610", "Adreno (TM) 619", "PowerVR Rogue GE8320")
            .forEach { assertTrue(it, PhoneTierClassifier.weakGpu(it)) }
        listOf("Mali-G715", "Mali-G710 MC10", "Mali-G77", "Adreno (TM) 642L", "Adreno (TM) 740", "Mali-G925-Immortalis MC12", "")
            .forEach { assertFalse(it, PhoneTierClassifier.weakGpu(it)) }
    }
}
