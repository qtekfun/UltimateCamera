package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraNamesTest {
    @Test
    fun vendorKeysAreEverythingOutsideTheAndroidNamespace() {
        assertTrue(CameraNames.isVendorKey("com.oplus.feature.video.hdr"))
        assertTrue(CameraNames.isVendorKey("xiaomi.camera.supportedfeatures"))
        assertFalse(CameraNames.isVendorKey("android.lens.facing"))
    }

    @Test
    fun capabilityNamesAreStableForTheCatalogue() {
        assertEquals("DYNAMIC_RANGE_TEN_BIT", CameraNames.capability(18))
        assertEquals("LOGICAL_MULTI_CAMERA", CameraNames.capability(11))
        assertEquals("COLOR_SPACE_PROFILES", CameraNames.capability(20))
        assertEquals("UNKNOWN_999", CameraNames.capability(999))
    }

    @Test
    fun everyCapabilityUpToColorSpaceProfilesHasItsOwnName() {
        val names = (0..20).map(CameraNames::capability)
        assertTrue(names.none { it.startsWith("UNKNOWN") })
        assertEquals(names.size, names.toSet().size)
        assertEquals("BACKWARD_COMPATIBLE", CameraNames.capability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE))
        assertEquals("MANUAL_SENSOR", CameraNames.capability(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR))
    }

    @Test
    fun dynamicRangeProfilesIncludingEveryDolbyVisionVariant() {
        val expected = mapOf(
            DynamicRangeProfiles.STANDARD to "STANDARD",
            DynamicRangeProfiles.HLG10 to "HLG10",
            DynamicRangeProfiles.HDR10 to "HDR10",
            DynamicRangeProfiles.HDR10_PLUS to "HDR10_PLUS",
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF to "DOLBY_VISION_10B_HDR_REF",
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO to "DOLBY_VISION_10B_HDR_REF_PO",
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM to "DOLBY_VISION_10B_HDR_OEM",
            DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO to "DOLBY_VISION_10B_HDR_OEM_PO",
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF to "DOLBY_VISION_8B_HDR_REF",
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO to "DOLBY_VISION_8B_HDR_REF_PO",
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM to "DOLBY_VISION_8B_HDR_OEM",
            DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO to "DOLBY_VISION_8B_HDR_OEM_PO"
        )
        expected.forEach { (value, name) -> assertEquals(name, CameraNames.dynamicRangeProfile(value)) }
        assertEquals("UNKNOWN_8192", CameraNames.dynamicRangeProfile(8192))
    }

    @Test
    fun facingAndHardwareLevel() {
        assertEquals("BACK", CameraNames.facing(CameraCharacteristics.LENS_FACING_BACK))
        assertEquals("FRONT", CameraNames.facing(CameraCharacteristics.LENS_FACING_FRONT))
        assertEquals("EXTERNAL", CameraNames.facing(CameraCharacteristics.LENS_FACING_EXTERNAL))
        assertEquals("UNKNOWN", CameraNames.facing(null))
        assertEquals("LEGACY", CameraNames.hardwareLevel(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY))
        assertEquals("LIMITED", CameraNames.hardwareLevel(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED))
        assertEquals("FULL", CameraNames.hardwareLevel(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))
        assertEquals("LEVEL_3", CameraNames.hardwareLevel(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3))
        assertEquals("EXTERNAL", CameraNames.hardwareLevel(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL))
        assertEquals("UNKNOWN_null", CameraNames.hardwareLevel(null))
    }

    @Test
    fun stabilizationAndTonemapModes() {
        assertEquals("OFF", CameraNames.videoStabilization(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF))
        assertEquals("ON", CameraNames.videoStabilization(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON))
        assertEquals("PREVIEW_STABILIZATION", CameraNames.videoStabilization(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION))
        assertEquals("UNKNOWN_7", CameraNames.videoStabilization(7))
        assertEquals("OFF", CameraNames.ois(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF))
        assertEquals("ON", CameraNames.ois(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON))
        assertEquals("UNKNOWN_5", CameraNames.ois(5))
        assertEquals("CONTRAST_CURVE", CameraNames.tonemap(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE))
        assertEquals("FAST", CameraNames.tonemap(CameraMetadata.TONEMAP_MODE_FAST))
        assertEquals("HIGH_QUALITY", CameraNames.tonemap(CameraMetadata.TONEMAP_MODE_HIGH_QUALITY))
        assertEquals("GAMMA_VALUE", CameraNames.tonemap(CameraMetadata.TONEMAP_MODE_GAMMA_VALUE))
        assertEquals("PRESET_CURVE", CameraNames.tonemap(CameraMetadata.TONEMAP_MODE_PRESET_CURVE))
        assertEquals("UNKNOWN_9", CameraNames.tonemap(9))
    }
}
