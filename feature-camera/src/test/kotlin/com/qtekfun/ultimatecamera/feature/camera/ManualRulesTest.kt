package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.camera.CameraControls
import com.qtekfun.ultimatecamera.core.camera.ControlMath
import com.qtekfun.ultimatecamera.core.camera.LiveExposure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRulesTest {
    @Test
    fun `manual exposure starts from the live auto values`() {
        val c = ManualRules.toManualExposure(CameraControls(aeLock = true), LiveExposure(iso = 640, exposureTimeNs = 10_000_000L), fps = 30)
        assertTrue(c.manualExposure)
        assertEquals(640, c.iso)
        assertEquals(10_000_000L, c.exposureTimeNs)
        assertFalse(c.aeLock)
    }

    @Test
    fun `without live values manual exposure uses the 180 degree shutter`() {
        val c = ManualRules.toManualExposure(CameraControls(), LiveExposure(), fps = 25)
        assertEquals(ControlMath.shutter180Ns(25), c.exposureTimeNs)
        assertEquals(CameraControls.DEFAULT_ISO, c.iso)
    }

    @Test
    fun `already manual stays as it is`() {
        val manual = CameraControls(manualExposure = true, iso = 200)
        assertSame(manual, ManualRules.toManualExposure(manual, LiveExposure(iso = 800), 30))
        val focus = ManualRules.toManualFocus(CameraControls(afLock = true), LiveExposure(focusDiopters = 2.5f))
        assertTrue(focus.manualFocus)
        assertEquals(2.5f, focus.focusDiopters)
        assertFalse(focus.afLock)
    }

    @Test
    fun `steps stay inside the list`() {
        assertEquals(0, ManualRules.step(0, -1, 5))
        assertEquals(4, ManualRules.step(4, 1, 5))
        assertEquals(3, ManualRules.step(2, 1, 5))
        assertEquals(0, ManualRules.step(0, 1, 0))
    }
}
