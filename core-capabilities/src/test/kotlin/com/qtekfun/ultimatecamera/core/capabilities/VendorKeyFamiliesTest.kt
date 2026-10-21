package com.qtekfun.ultimatecamera.core.capabilities

import org.junit.Assert.assertEquals
import org.junit.Test

class VendorKeyFamiliesTest {
    @Test
    fun namespaceKeepsTheOrganisationUnderADomain() {
        assertEquals("org.codeaurora", VendorKeyFamilies.namespace("org.codeaurora.qcamera3.video_hdr_mode.vhdr_mode"))
        assertEquals("com.vendor", VendorKeyFamilies.namespace("com.vendor.capture.metadata.sceneMode"))
        assertEquals("vendorcam", VendorKeyFamilies.namespace("vendorcam.camera.supportedfeatures"))
        assertEquals("com.flag", VendorKeyFamilies.namespace("com.flag"))
    }

    @Test
    fun familiesCountEachKeyOnceLargestFirst() {
        val camera = AdvertisedCamera(
            "0",
            "BACK",
            videoModes = emptyList(),
            vendorRequestKeys = listOf("org.codeaurora.a", "org.codeaurora.b", "org.codeaurora.c", "com.vendor.scene", "com.vendor.fps"),
            vendorCharacteristicKeys = listOf("org.codeaurora.c", "org.codeaurora.d"),
            vendorSessionKeys = listOf("com.vendor.fps")
        )
        assertEquals(
            listOf(VendorKeyFamily("org.codeaurora", keys = 4, sessionKeys = 0), VendorKeyFamily("com.vendor", keys = 2, sessionKeys = 1)),
            VendorKeyFamilies.of(camera)
        )
    }

    @Test
    fun aPlainCameraHasNoFamilies() {
        assertEquals(emptyList<VendorKeyFamily>(), VendorKeyFamilies.of(AdvertisedCamera("0", "BACK", videoModes = emptyList())))
    }
}
