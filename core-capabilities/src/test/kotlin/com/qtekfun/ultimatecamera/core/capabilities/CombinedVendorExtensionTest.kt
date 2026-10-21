package com.qtekfun.ultimatecamera.core.capabilities

import com.qtekfun.ultimatecamera.core.capabilities.CodecType.HEVC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedVendorExtensionTest {
    private val combined = CombinedVendorExtension(SensorHdrExtension, PacingVariants)
    private val identity = Fixtures.oppo.identity
    private val camera = AdvertisedCamera(
        "0",
        "BACK",
        videoModes = emptyList(),
        vendorRequestKeys = listOf(SensorHdrExtension.QTI_VHDR_MODE, PacingVariants.QTI_EXTRA_PREVIEW_BUFFERS),
        streamUseCase = true
    )
    private val plain = VideoConfig(3840, 2160, 60, HEVC, DynamicRange.HLG10, LutMode.HARDWARE)

    @Test
    fun `variants of every part, in part order`() {
        assertEquals(
            listOf(SensorHdrExtension.MODE, PacingVariants.QTI_EIS, PacingVariants.QTI_BUFFERS, PacingVariants.RECORD_USE_CASE),
            combined.variants(camera)
        )
        assertTrue(combined.appliesTo(identity, camera))
        assertFalse(combined.appliesTo(identity, camera.copy(vendorRequestKeys = emptyList(), streamUseCase = false)))
        // One part applying is enough.
        assertTrue(combined.appliesTo(identity, camera.copy(vendorRequestKeys = emptyList())))
    }

    @Test
    fun `each variant goes to the part that offers it, with its id`() {
        val hdr = plain.copy(vendorMode = SensorHdrExtension.MODE)
        assertEquals(SensorHdrExtension.sessionParameters(camera, hdr), combined.sessionParameters(camera, hdr))
        assertEquals(SensorHdrExtension.id, combined.idFor(camera, hdr))
        val eis = plain.copy(vendorMode = PacingVariants.QTI_EIS)
        assertEquals(PacingVariants.QTI_EIS_VIDEO_SESSION, combined.sessionType(camera, eis))
        assertEquals(PacingVariants.id, combined.idFor(camera, eis))
        val buffers = plain.copy(vendorMode = PacingVariants.QTI_BUFFERS)
        assertEquals(PacingVariants.QTI_EXTRA_PREVIEW_BUFFERS, combined.sessionParameters(camera, buffers).single().key)
        assertTrue(combined.recordUseCase(camera, plain.copy(vendorMode = PacingVariants.RECORD_USE_CASE)))
        assertFalse(combined.recordUseCase(camera, buffers))
    }

    @Test
    fun `planning follows the owner, so pacing variants skip 30 fps and sensor HDR does not`() {
        assertTrue(combined.plans(camera, plain.copy(fps = 30, vendorMode = SensorHdrExtension.MODE)))
        assertFalse(combined.plans(camera, plain.copy(fps = 30, vendorMode = PacingVariants.QTI_EIS)))
        assertTrue(combined.plans(camera, plain.copy(vendorMode = PacingVariants.QTI_EIS)))
    }

    @Test
    fun `a plain configuration gets exactly what the first part gives it`() {
        assertEquals(SensorHdrExtension.sessionParameters(camera, plain), combined.sessionParameters(camera, plain))
        assertTrue(combined.sessionParameters(camera, plain).isEmpty())
        assertNull(combined.sessionType(camera, plain))
        assertFalse(combined.recordUseCase(camera, plain))
        assertEquals(SensorHdrExtension.id, combined.idFor(camera, plain))
    }

    @Test
    fun `a variant no part offers gets nothing`() {
        val unknown = plain.copy(vendorMode = "unknown")
        assertTrue(combined.sessionParameters(camera, unknown).isEmpty())
        assertNull(combined.sessionType(camera, unknown))
        assertFalse(combined.plans(camera, unknown))
        assertEquals(combined.id, combined.idFor(camera, unknown))
        // Offered by a part only where that part finds its keys.
        val mi = plain.copy(vendorMode = PacingVariants.MI_VIDEO)
        assertNull(combined.sessionType(camera, mi))
    }
}
