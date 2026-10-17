package com.qtekfun.ultimatecamera.core.gpu.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D83: the base rendering now brightens an Ultra HDR photo's SDR base (white wall 206 → about 240). The HDR rendition
 * must not get the camera's full gain on top of that: the gain map is rebased so highlights land where they were.
 */
class GainmapRebaseTest {
    /** The owner's camera map: ratio 1…2^2.3, gamma 1, no offsets. */
    private val math = GainmapRebase(floatArrayOf(1f, 1f, 1f), FloatArray(3) { 4.925f }, floatArrayOf(1f, 1f, 1f), FloatArray(3))

    private fun grey(v: Int) = (v shl 16) or (v shl 8) or v

    @Test
    fun aBrighterSdrBaseKeepsTheHdrHighlight() {
        val before = intArrayOf(grey(206), grey(120), grey(240))
        val after = intArrayOf(grey(241), grey(130), grey(240))
        val gain = intArrayOf(200, 0, 128)
        val rebased = math.rebase(gain, 1, before, after)
        for (i in before.indices) {
            val old = math.hdr(Srgb.decode((before[i] and 0xFF) / 255f), gain[i])
            val new = math.hdr(Srgb.decode((after[i] and 0xFF) / 255f), rebased[i])
            if (gain[i] > 0) assertEquals("pixel $i", old, new, old * 0.03)
        }
        // A highlight brightened in SDR needs less gain; an unchanged pixel keeps its gain.
        assertTrue(rebased[0] < gain[0])
        assertEquals(gain[2], rebased[2])
        // Mid-tones (gain 1) cannot go below the map's minimum: their HDR simply follows the new SDR picture.
        assertEquals(0, rebased[1])
    }

    @Test
    fun threeChannelMapsAreRebasedPerChannel() {
        val before = intArrayOf((200 shl 16) or (210 shl 8) or 220)
        val after = intArrayOf((235 shl 16) or (240 shl 8) or 245)
        val gain = intArrayOf(180, 180, 180)
        val rebased = math.rebase(gain, 3, before, after)
        assertTrue(rebased.all { it < 180 })
        assertTrue("red brightened most", rebased[0] < rebased[2])
    }

    @Test
    fun packedAlphaAndArgbMapsRoundTrip() {
        val before = intArrayOf(grey(206))
        val after = intArrayOf(grey(241))
        val alpha = math.rebasePacked(intArrayOf(200 shl 24), true, before, after)
        assertEquals(math.rebase(intArrayOf(200), 1, before, after)[0], alpha[0] ushr 24)
        assertEquals(0, alpha[0] and 0xFFFFFF)
        val argb = math.rebasePacked(intArrayOf(-0x1000000 or (200 shl 16) or (100 shl 8) or 0), false, before, after)
        val expected = math.rebase(intArrayOf(200, 100, 0), 3, before, after)
        assertEquals(-0x1000000 or (expected[0] shl 16) or (expected[1] shl 8) or expected[2], argb[0])
    }

    @Test
    fun aFlatMapIsLeftAlone() {
        val flat = GainmapRebase(floatArrayOf(2f, 2f, 2f), floatArrayOf(2f, 2f, 2f), floatArrayOf(1f, 1f, 1f), FloatArray(3))
        assertEquals(77, flat.rebase(intArrayOf(77), 1, intArrayOf(grey(100)), intArrayOf(grey(200)))[0])
    }
}
