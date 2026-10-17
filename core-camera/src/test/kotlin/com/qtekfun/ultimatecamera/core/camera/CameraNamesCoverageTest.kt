package com.qtekfun.ultimatecamera.core.camera

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every name the capability report can print is non-blank and unknown values stay visible, never a crash. */
class CameraNamesCoverageTest {
    @Test
    fun everyCameraNameIsReadable() {
        val names = (-1..20).flatMap { v ->
            listOf(
                CameraNames.capability(v),
                CameraNames.facing(v),
                CameraNames.hardwareLevel(v),
                CameraNames.videoStabilization(v),
                CameraNames.ois(v),
                CameraNames.tonemap(v)
            )
        } + listOf(CameraNames.facing(null), CameraNames.hardwareLevel(null)) +
            (0..12).map { CameraNames.dynamicRangeProfile(1L shl it) }
        assertTrue(names.all { it.isNotBlank() })
        assertTrue(CameraNames.isVendorKey("com.oem.key"))
        assertTrue(!CameraNames.isVendorKey("android.control.aeMode"))
    }

    @Test
    fun cameraInfoRoundTripsThroughTheReport() {
        val info = CameraInfo(
            id = "0",
            physicalIds = listOf("2"),
            facing = "back",
            hardwareLevel = "FULL",
            capabilities = listOf("MANUAL_SENSOR"),
            focalLengthsMm = listOf(6.9f),
            apertures = listOf(1.7f),
            sensorSizeMm = listOf(9.8f, 7.4f),
            pixelArray = listOf(8160, 6144),
            isoRange = listOf(50, 6400),
            exposureRangeNs = listOf(10_000L, 1_000_000_000L),
            aeTargetFpsRanges = listOf("15-30"),
            videoStabilizationModes = listOf("OFF"),
            opticalStabilizationModes = listOf("ON"),
            zoomRatioRange = listOf(1f, 10f),
            maxDigitalZoom = 10f,
            minFocusDistanceDiopters = 10f,
            dynamicRangeProfiles = listOf("HLG10"),
            recommendedTenBitProfile = "HLG10",
            colorSpaces = emptyList(),
            tonemapModes = listOf("HIGH_QUALITY"),
            videoModes = listOf(StreamMode(3840, 2160, 60)),
            highSpeedModes = emptyList(),
            vendorCharacteristicKeys = emptyList(),
            vendorRequestKeys = emptyList(),
            vendorResultKeys = emptyList(),
            photoHdr = listOf("extension_hdr", "bracket")
        )
        val json = Json.encodeToString(CameraInfo.serializer(), info)
        assertEquals(info, Json.decodeFromString(CameraInfo.serializer(), json))
        assertEquals("3840x2160@60", info.videoModes.first().toString())
    }
}
