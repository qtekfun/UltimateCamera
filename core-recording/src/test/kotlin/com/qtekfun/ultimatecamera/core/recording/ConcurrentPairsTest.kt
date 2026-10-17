package com.qtekfun.ultimatecamera.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConcurrentPairsTest {
    private val front = setOf("1", "5")

    @Test
    fun noCombinationMeansNoSecondCamera() {
        assertNull(ConcurrentPairs.partner(emptySet(), "0") { it in front })
        assertNull(ConcurrentPairs.partner(setOf(setOf("2", "3")), "0") { it in front })
    }

    @Test
    fun aFrontCameraIsPreferred() {
        val sets = setOf(setOf("0", "2"), setOf("0", "1"))
        assertEquals("1", ConcurrentPairs.partner(sets, "0") { it in front })
    }

    @Test
    fun otherwiseTheLowestIdOfTheCombinations() {
        val sets = setOf(setOf("0", "4"), setOf("0", "2"))
        assertEquals("2", ConcurrentPairs.partner(sets, "0") { false })
    }

    @Test
    fun aChosenPairWinsWhenItHoldsTheMainCameraAndThePhoneReportsIt() {
        val sets = setOf(setOf("0", "1"), setOf("0", "2"))
        assertEquals("2", ConcurrentPairs.partner(sets, "0", "0,2") { it in front })
        // Not reported, or without the main camera: the automatic choice (front first).
        assertEquals("1", ConcurrentPairs.partner(sets, "0", "0,3") { it in front })
        assertEquals("1", ConcurrentPairs.partner(sets, "0", "1,2") { it in front })
        assertEquals("1", ConcurrentPairs.partner(sets, "0", null) { it in front })
    }

    @Test
    fun optionsListEveryPairWithTheMainCameraFirstAndBlockTheOthers() {
        val sets = setOf(setOf("0", "1"), setOf("2", "1"), setOf("0", "10", "2"))
        val options = ConcurrentPairs.options(sets, mainId = "0", refused = setOf("10"))
        assertEquals(
            listOf(
                PairOption(listOf("0", "1")),
                PairOption(listOf("0", "2")),
                PairOption(listOf("0", "10"), PairBlock.REFUSED),
                PairOption(listOf("1", "2"), PairBlock.OTHER_MAIN_CAMERA),
                PairOption(listOf("2", "10"), PairBlock.OTHER_MAIN_CAMERA)
            ),
            options
        )
        assertEquals("0,10", options[2].key)
    }

    @Test
    fun sizePrefers1080pThenTheLargestWideSizeBelowIt() {
        assertEquals(1920 to 1080, ConcurrentPairs.size(listOf(3840 to 2160, 1920 to 1080, 1280 to 720)))
        assertEquals(1280 to 720, ConcurrentPairs.size(listOf(3840 to 2160, 1440 to 1080, 1280 to 720)))
        assertEquals(1440 to 1080, ConcurrentPairs.size(listOf(4000 to 3000, 1440 to 1080, 640 to 480)))
        assertNull(ConcurrentPairs.size(listOf(4000 to 3000)))
    }
}
