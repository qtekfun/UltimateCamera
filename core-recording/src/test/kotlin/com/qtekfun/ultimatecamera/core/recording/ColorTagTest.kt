package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.codec.ColorTag
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorTagTest {
    private val lut = CubeLut.identity(2)

    @Test
    fun cameraSignalStaysHlg() {
        assertEquals(ColorTag.HLG_BT2020, CameraController.colorTag(tenBit = true, setup = ColorSetup.NONE))
    }

    @Test
    fun ucLogIsTaggedSdrInBt2020() {
        assertEquals(ColorTag.SDR_BT2020, CameraController.colorTag(true, ColorSetup(ucLog = true, recordLut = lut, monitorLut = null)))
    }

    @Test
    fun aBakedLutIsTaggedRec709() {
        assertEquals(ColorTag.SDR_BT709, CameraController.colorTag(true, ColorSetup(ucLog = false, recordLut = lut, monitorLut = null)))
    }

    @Test
    fun eightBitKeepsTheEncoderDefault() {
        assertNull(CameraController.colorTag(false, ColorSetup(ucLog = false, recordLut = lut, monitorLut = null)))
    }
}
