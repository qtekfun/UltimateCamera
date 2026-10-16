package com.qtekfun.ultimatecamera.core.camera

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
        assertEquals("UNKNOWN_999", CameraNames.capability(999))
    }
}
