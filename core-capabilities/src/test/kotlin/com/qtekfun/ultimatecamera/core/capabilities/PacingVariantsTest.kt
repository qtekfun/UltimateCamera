package com.qtekfun.ultimatecamera.core.capabilities

import com.qtekfun.ultimatecamera.core.capabilities.CodecType.HEVC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacingVariantsTest {
    private val ext = PacingVariants
    private val uhd60 = VideoConfig(3840, 2160, 60, HEVC, DynamicRange.HLG10, LutMode.HARDWARE)

    private fun camera(vararg keys: String, useCase: Boolean = false) =
        AdvertisedCamera("0", "BACK", videoModes = emptyList(), vendorRequestKeys = keys.toList(), streamUseCase = useCase)

    @Test
    fun `a camera with no matching keys and no use case capability gets none`() {
        val none = camera("com.google.internal.IsCameraApp1P", "com.oplus.video.hdr.mode", "org.quic.camera2.foo")
        assertTrue(ext.variants(none).isEmpty())
        assertFalse(ext.appliesTo(Fixtures.oppo.identity, none))
        assertTrue(ext.sessionParameters(none, uhd60.copy(vendorMode = PacingVariants.QTI_BUFFERS)).isEmpty())
        assertNull(ext.sessionType(none, uhd60.copy(vendorMode = PacingVariants.MI_VIDEO)))
        assertFalse(ext.recordUseCase(none, uhd60.copy(vendorMode = PacingVariants.RECORD_USE_CASE)))
    }

    @Test
    fun `vendor-prefixed keys give the operating modes, the exact keys the parameter variants, in order`() {
        val all = camera(
            "com.xiaomi.camera.feature",
            PacingVariants.XIAOMI_RECORD_CONTROL,
            "org.codeaurora.qcamera3.foo",
            PacingVariants.QTI_EXTRA_PREVIEW_BUFFERS,
            useCase = true
        )
        assertEquals(
            listOf(
                PacingVariants.MI_NATIVE,
                PacingVariants.MI_VIDEO,
                PacingVariants.QTI_EIS,
                PacingVariants.MI_VIDEO_EIS,
                PacingVariants.MI_HSR60,
                PacingVariants.QTI_BUFFERS,
                PacingVariants.MI_RECORDING,
                PacingVariants.RECORD_USE_CASE
            ),
            ext.variants(all)
        )
        assertEquals(
            listOf(PacingVariants.MI_NATIVE, PacingVariants.MI_VIDEO, PacingVariants.MI_VIDEO_EIS, PacingVariants.MI_HSR60),
            ext.variants(camera("xiaomi.pro.video.log.enabled"))
        )
        assertEquals(listOf(PacingVariants.QTI_EIS), ext.variants(camera("org.codeaurora.qcamera3.foo")))
        assertEquals(listOf(PacingVariants.RECORD_USE_CASE), ext.variants(camera(useCase = true)))
        // The parameter variants need their exact key, not only the family.
        assertFalse(PacingVariants.QTI_BUFFERS in ext.variants(camera("org.codeaurora.qcamera3.sessionParameters.EISMode")))
        assertFalse(PacingVariants.MI_RECORDING in ext.variants(camera("xiaomi.video.other")))
    }

    @Test
    fun `the stock video recipe sets the video mode, stabilization and the recording switch, nothing about the caller`() {
        val native = uhd60.copy(vendorMode = PacingVariants.MI_NATIVE)
        val withSwitch = camera("com.xiaomi.camera.feature", PacingVariants.XIAOMI_RECORD_SWITCH, PacingVariants.XIAOMI_RECORD_CONTROL)
        assertEquals(0x8004, ext.sessionType(withSwitch, native))
        assertEquals(
            listOf(
                VendorParameter(PacingVariants.VIDEO_STABILIZATION, VendorValueType.INT, "1"),
                VendorParameter(PacingVariants.OPTICAL_STABILIZATION, VendorValueType.INT, "0"),
                // The switch the stock app sets wins over the older flag.
                VendorParameter(PacingVariants.XIAOMI_RECORD_SWITCH, VendorValueType.INT, "1")
            ),
            ext.sessionParameters(withSwitch, native)
        )
        assertEquals(2, ext.sessionParameters(camera("xiaomi.foo"), native).size)
        val keys = ext.sessionParameters(withSwitch, native).map { it.key }
        assertTrue(keys.none { "process" in it.lowercase() || "module" in it.lowercase() || "thirdparty" in it.lowercase() })
    }

    @Test
    fun `operating modes per variant`() {
        val cam = camera("xiaomi.foo", "org.codeaurora.bar")
        assertEquals(0x8004, ext.sessionType(cam, uhd60.copy(vendorMode = PacingVariants.MI_VIDEO)))
        assertEquals(0xF004, ext.sessionType(cam, uhd60.copy(vendorMode = PacingVariants.QTI_EIS)))
        assertEquals(0x8019, ext.sessionType(cam, uhd60.copy(vendorMode = PacingVariants.MI_VIDEO_EIS)))
        assertEquals(0x803C, ext.sessionType(cam, uhd60.copy(vendorMode = PacingVariants.MI_HSR60)))
        listOf(PacingVariants.MI_VIDEO, PacingVariants.QTI_EIS, PacingVariants.MI_VIDEO_EIS, PacingVariants.MI_HSR60).forEach {
            assertTrue(ext.sessionParameters(cam, uhd60.copy(vendorMode = it)).isEmpty())
        }
        // Never for the plain session.
        assertNull(ext.sessionType(cam, uhd60))
        assertTrue(ext.sessionParameters(cam, uhd60).isEmpty())
    }

    @Test
    fun `parameter variants set their keys on a regular session`() {
        val buffers = uhd60.copy(vendorMode = PacingVariants.QTI_BUFFERS)
        val onlyBuffers = camera(PacingVariants.QTI_EXTRA_PREVIEW_BUFFERS)
        assertEquals(
            listOf(VendorParameter(PacingVariants.QTI_EXTRA_PREVIEW_BUFFERS, VendorValueType.INT, "4")),
            ext.sessionParameters(onlyBuffers, buffers)
        )
        assertNull(ext.sessionType(onlyBuffers, buffers))
        val withEis = camera(PacingVariants.QTI_EXTRA_PREVIEW_BUFFERS, PacingVariants.QTI_EIS_MODE)
        assertEquals(
            listOf(
                VendorParameter(PacingVariants.QTI_EXTRA_PREVIEW_BUFFERS, VendorValueType.INT, "4"),
                VendorParameter(PacingVariants.QTI_EIS_MODE, VendorValueType.INT, "0")
            ),
            ext.sessionParameters(withEis, buffers)
        )
        val recording = uhd60.copy(vendorMode = PacingVariants.MI_RECORDING)
        assertEquals(
            listOf(VendorParameter(PacingVariants.XIAOMI_RECORD_CONTROL, VendorValueType.INT, "1")),
            ext.sessionParameters(camera(PacingVariants.XIAOMI_RECORD_CONTROL), recording)
        )
    }

    @Test
    fun `the use case variant has no vendor key and needs the capability`() {
        val useCase = uhd60.copy(vendorMode = PacingVariants.RECORD_USE_CASE)
        val cam = camera(useCase = true)
        assertTrue(ext.recordUseCase(cam, useCase))
        assertTrue(ext.sessionParameters(cam, useCase).isEmpty())
        assertNull(ext.sessionType(cam, useCase))
        assertFalse(ext.recordUseCase(cam, uhd60))
        assertFalse(ext.recordUseCase(camera(), useCase))
    }

    @Test
    fun `planned for 4K60 10-bit only, never 4K30`() {
        val device = Fixtures.oppo.copy(
            cameras = Fixtures.oppo.cameras.map { it.copy(vendorRequestKeys = listOf("xiaomi.foo", "org.codeaurora.bar"), streamUseCase = true) }
        )
        val plan = ProbePlanner().plan(device, plans = ext::plans) { ext.variants(it) }
        val variants = plan.filter { it.config.vendorMode != null }
        assertEquals(ext.variants(device.cameras[0]), variants.map { it.config.vendorMode }.distinct())
        assertTrue(variants.isNotEmpty())
        assertTrue(variants.all { it.config.width == 3840 && it.config.fps == 60 && it.config.tenBit })
        // The same planner keeps 4K30 for a part that does not filter.
        val unfiltered = ProbePlanner().plan(device) { ext.variants(it) }.filter { it.config.vendorMode != null }
        assertTrue(unfiltered.any { it.config.fps == 30 })
    }
}
