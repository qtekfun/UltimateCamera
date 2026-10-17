package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.capabilities.SensorHdrMode
import com.qtekfun.ultimatecamera.core.capabilities.VendorParameter
import com.qtekfun.ultimatecamera.core.capabilities.VendorValueType
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrViewfinderTest {
    private val lut = CubeLut.identity(2)

    @Test
    fun `HLG signal as it comes on an HDR display is shown as HDR`() {
        assertTrue(HdrViewfinder.hlgPreview(allowed = true, tenBit = true, setup = ColorSetup.NONE))
    }

    @Test
    fun `a baked recording LUT alone keeps the HLG preview (the monitor shows the camera signal)`() {
        assertTrue(HdrViewfinder.hlgPreview(true, true, ColorSetup(ucLog = false, recordLut = lut, monitorLut = null)))
    }

    @Test
    fun `setting off, SDR display or 8-bit take keep the SDR preview`() {
        assertFalse(HdrViewfinder.hlgPreview(allowed = false, tenBit = true, setup = ColorSetup.NONE))
        assertFalse(HdrViewfinder.hlgPreview(allowed = true, tenBit = false, setup = ColorSetup.NONE))
    }

    @Test
    fun `a monitor LUT or UC-Log outputs SDR so the preview stays SDR`() {
        assertFalse(HdrViewfinder.hlgPreview(true, true, ColorSetup(ucLog = false, recordLut = null, monitorLut = lut)))
        assertFalse(HdrViewfinder.hlgPreview(true, true, ColorSetup(ucLog = true, recordLut = null, monitorLut = null)))
    }

    @Test
    fun `a vendor session applies only to the sizes and rates its variant held at`() {
        val mode = SensorHdrMode("k", listOf(VendorParameter("k", VendorValueType.INT, "1")))
        val session = VendorSession.of(mode, listOf(HeldMode(3840, 2160, 30)))
        assertEquals(mode.parameters, session.parameters)
        assertTrue(session.allows(3840, 2160, 30))
        assertTrue(session.allows(3840, 2160, 24))
        assertFalse(session.allows(3840, 2160, 60))
        assertFalse(session.allows(1920, 1080, 30))
    }
}
