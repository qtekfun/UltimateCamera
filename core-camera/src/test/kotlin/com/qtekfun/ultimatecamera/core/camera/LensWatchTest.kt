package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LensWatchTest {
    @Test
    fun `a zoom that moves the logical camera to another lens is a switch, the first lens and silence are not`() {
        // D113: at 3x the HAL moved to the telephoto while the main lens's colour matrix and gains were still sent.
        val watch = LensWatch()
        assertFalse(watch.switched(null))
        assertFalse(watch.switched("2"))
        assertFalse(watch.switched("2"))
        assertFalse(watch.switched(null))
        assertEquals("2", watch.lens)
        assertTrue(watch.switched("4"))
        assertEquals("4", watch.lens)
        assertTrue(watch.switched("2"))
    }

    @Test
    fun `after a switch the camera's white balance is copied once it converged, or after a bounded wait`() {
        val latch = AwbLatch()
        // At the start of a stream any automatic result is copied, as before.
        assertTrue(latch.copies(converged = false))
        latch.restart()
        assertTrue(latch.settling)
        assertFalse(latch.copies(converged = false))
        assertTrue(latch.copies(converged = true))
        assertFalse(latch.settling)
        latch.restart()
        val waited = (1..AwbLatch.SETTLE_FRAMES).count { !latch.copies(converged = false) }
        assertEquals(AwbLatch.SETTLE_FRAMES - 1, waited)
        assertFalse(latch.settling)
        assertTrue(latch.copies(converged = false))
    }
}
