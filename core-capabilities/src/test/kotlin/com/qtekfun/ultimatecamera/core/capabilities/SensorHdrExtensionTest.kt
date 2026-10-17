package com.qtekfun.ultimatecamera.core.capabilities

import com.qtekfun.ultimatecamera.core.capabilities.CodecType.HEVC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorHdrExtensionTest {
    private val ext = SensorHdrExtension

    @Test
    fun `no vendor keys means no sensor HDR`() {
        assertNull(ext.detect(emptyList(), emptyList()))
        assertNull(ext.detect(listOf("com.google.internal.IsCameraApp1P"), listOf("com.google.foo")))
    }

    @Test
    fun `the Qualcomm vhdr request key is set to 1 as an int`() {
        val mode = ext.detect(listOf(SensorHdrExtension.QTI_VHDR_MODE, SensorHdrExtension.QUIC_HDR_VIDEO_MODE), emptyList())!!
        assertEquals(listOf(VendorParameter(SensorHdrExtension.QTI_VHDR_MODE, VendorValueType.INT, "1")), mode.parameters)
        assertNull(mode.sessionType)
    }

    @Test
    fun `the QUIC stream-config key is a byte`() {
        val mode = ext.detect(listOf(SensorHdrExtension.QUIC_HDR_VIDEO_MODE), emptyList())!!
        assertEquals(VendorValueType.BYTE, mode.parameters.single().type)
    }

    @Test
    fun `an Oplus video HDR request key is found by name, other Oplus keys are not`() {
        assertNull(ext.detect(listOf("com.oplus.camera.mode", "com.oplus.log.video.mode", "com.oplus.video.stabilization.mode"), emptyList()))
        val mode = ext.detect(listOf("com.oplus.camera.mode", "com.oplus.video.hdr.mode"), emptyList())!!
        assertEquals("com.oplus.video.hdr.mode", mode.parameters.single().key)
    }

    @Test
    fun `a QTI video HDR mode list without a request key gives the ZZHDR session type`() {
        val list = "org.quic.camera2.streamconfigs.info.available_video_hdr_modes"
        val mode = ext.detect(listOf("org.codeaurora.qcamera3.sessionParameters.SensorModesInConfig"), listOf(list), mapOf(list to "0,1"))!!
        assertEquals(SensorHdrExtension.QTI_ZZHDR_SESSION, mode.sessionType)
        assertTrue(mode.parameters.isEmpty())
        assertTrue(mode.description.contains("0xF002"))
    }

    @Test
    fun `a readable QTI list holding only off denies sensor HDR even with the request key`() {
        val list = "org.codeaurora.qcamera3.video_hdr_mode.vhdr_supported_modes"
        assertNull(ext.detect(listOf(SensorHdrExtension.QTI_VHDR_MODE), listOf(list), mapOf(list to "0")))
        // Unreadable (no value) is not a denial.
        assertEquals(SensorHdrExtension.QTI_VHDR_MODE, ext.detect(listOf(SensorHdrExtension.QTI_VHDR_MODE), listOf(list))!!.source)
    }

    @Test
    fun `Xiaomi keys give the HDR10 operating mode`() {
        assertEquals(SensorHdrExtension.XIAOMI_HDR10_SESSION, ext.detect(listOf("xiaomi.pro.video.log.enabled"), emptyList())!!.sessionType)
    }

    @Test
    fun `parameters and session type only for the variant`() {
        val camera = AdvertisedCamera("0", "BACK", videoModes = emptyList(), vendorRequestKeys = listOf(SensorHdrExtension.QTI_VHDR_MODE))
        val plain = VideoConfig(3840, 2160, 30, HEVC, DynamicRange.HLG10, LutMode.HARDWARE)
        assertTrue(ext.sessionParameters(camera, plain).isEmpty())
        assertEquals(1, ext.sessionParameters(camera, plain.copy(vendorMode = SensorHdrExtension.MODE)).size)
        assertEquals(listOf(SensorHdrExtension.MODE), ext.variants(camera))
        assertFalse(ext.appliesTo(Fixtures.pixel8.device.identity, camera.copy(vendorRequestKeys = emptyList())))
    }

    @Test
    fun `variants are planned after the plain ladder for the 4K 10-bit rungs only`() {
        val device = Fixtures.pixel8.device
        val plain = ProbePlanner().plan(device)
        val plan = ProbePlanner().plan(device) { listOf(SensorHdrExtension.MODE) }
        assertEquals(plain, plan.take(plain.size))
        val variants = plan.drop(plain.size)
        assertTrue(variants.isNotEmpty())
        assertTrue(variants.all { it.config.vendorMode == SensorHdrExtension.MODE && it.config.tenBit && it.config.width == 3840 })
        assertEquals(variants.sortedBy { it.config.cost }, variants)
    }

    @Test
    fun `plain and variant results never imply each other and a variant never joins the plain ladder`() {
        val plain = VideoConfig(3840, 2160, 30, HEVC, DynamicRange.HLG10, LutMode.HARDWARE)
        val variant = plain.copy(vendorMode = SensorHdrExtension.MODE)
        val rejected = ProbeResult("0", plain, true, ProbeOutcome.SESSION_REJECTED)
        assertNull(Implications.decide(variant, listOf(rejected)))
        val held = ProbeResult("0", variant, true, ProbeOutcome.ACCEPTED_AND_HELD)
        assertNull(Implications.decide(plain, listOf(held)))
        assertFalse(variant.covers(plain))

        val caps = DeviceCapabilities(
            identity = Fixtures.pixel8.device.identity,
            engineVersion = ProbeEngine.ENGINE_VERSION,
            cameras = listOf(CameraCapabilities(Fixtures.pixel8.device.camera("0")!!, listOf(held)))
        )
        val selector = CapabilitySelector(caps)
        assertTrue(selector.heldLadder("0").isEmpty())
        assertEquals(listOf(variant), selector.heldVariants("0", SensorHdrExtension.MODE))
        val dropped = held.copy(outcome = ProbeOutcome.DROPS)
        val noHeld = caps.copy(cameras = listOf(CameraCapabilities(Fixtures.pixel8.device.camera("0")!!, listOf(dropped))))
        assertTrue(CapabilitySelector(noHeld).heldVariants("0", SensorHdrExtension.MODE).isEmpty())
    }
}
