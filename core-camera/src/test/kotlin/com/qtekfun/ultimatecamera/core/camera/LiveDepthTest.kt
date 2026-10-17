package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDepthTest {
    @Test
    fun onlyTheLastRequestOfEachBurstFillsTheDepthOutputs() {
        assertEquals(listOf(false, false, true), LiveDepthBurst.pattern(3))
        assertEquals(listOf(false, false, false, true), LiveDepthBurst.pattern(4))
        assertEquals(listOf(true), LiveDepthBurst.pattern(1))
        assertEquals("a bad rate means every request, never none", listOf(true), LiveDepthBurst.pattern(0))
    }

    @Test
    fun withoutLiveDepthTheFirstImageIsTheStills() {
        // Only the still fills the outputs; physical cameras may stamp it with their own clock.
        assertTrue(AuxPick.done(imageNanos = 5_000_000_000, stillNanos = 1_000_000_000, liveWasOn = false))
    }

    @Test
    fun withLiveDepthOnlyTheStillsTimestampEndsTheWaitEarly() {
        val still = 2_000_000_000L
        assertFalse("a preview frame in flight", AuxPick.done(still - 33_000_000, still, liveWasOn = true))
        assertTrue(AuxPick.done(still, still, liveWasOn = true))
        assertTrue(AuxPick.done(still + AuxPick.SAME_REQUEST_NANOS, still, liveWasOn = true))
        assertFalse(AuxPick.done(still + 2 * AuxPick.SAME_REQUEST_NANOS, still, liveWasOn = true))
    }

    @Test
    fun stereoImagesOfOneRequestArePaired() {
        val pairer = StereoFramePairer<String>(toleranceNanos = 20_000_000)
        assertNull(pairer.offer(0, 1_000_000_000, "main"))
        val pair = pairer.offer(1, 1_005_000_000, "second")
        assertEquals("main" to "second", pair)
        // Consumed: the next image starts a new pair.
        assertNull(pairer.offer(1, 1_010_000_000, "late second"))
    }

    @Test
    fun anUnmatchedImageIsReplacedByTheNextOfItsOutput() {
        val pairer = StereoFramePairer<String>(toleranceNanos = 20_000_000)
        assertNull(pairer.offer(0, 1_000_000_000, "old main"))
        // The second camera's image of that request was dropped; its next one is 100 ms later.
        assertNull(pairer.offer(1, 1_100_000_000, "second"))
        assertEquals("new main" to "second", pairer.offer(0, 1_101_000_000, "new main"))
    }

    @Test
    fun clearingForgetsHalfPairs() {
        val pairer = StereoFramePairer<String>(toleranceNanos = 20_000_000)
        pairer.offer(0, 1_000_000_000, "main")
        pairer.clear()
        assertNull(pairer.offer(1, 1_000_000_000, "second"))
        assertThrows(IllegalArgumentException::class.java) { pairer.offer(2, 0, "third") }
    }

    @Test
    fun stereoImagesAreCutToTheirCommonSize() {
        val image = LiveImage(byteArrayOf(1, 2, 3, 4, 5, 6), width = 3, height = 2)
        assertArrayEquals(byteArrayOf(1, 2, 4, 5), image.crop(2, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3), image.crop(3, 1))
        assertSame(image.luma, image.crop(3, 2))
    }
}
