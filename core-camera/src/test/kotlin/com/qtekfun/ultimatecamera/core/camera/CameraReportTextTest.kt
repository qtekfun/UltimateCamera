package com.qtekfun.ultimatecamera.core.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraReportTextTest {
    @Test
    fun sizeTablesAreVendorKeysThatNameSizesOrStreams() {
        assertTrue(CameraReportText.isSizeKey("xiaomi.scaler.availableLimitStreamConfigurations"))
        assertTrue(CameraReportText.isSizeKey("org.codeaurora.qcamera3.sensor_meta_data.sensor_mode_info.resolution"))
        assertFalse(CameraReportText.isSizeKey("android.scaler.availableStreamConfigurations"))
        assertFalse(CameraReportText.isSizeKey("com.vendor.hdr.mode"))
    }

    @Test
    fun sizesAreLargestFirstWithoutRepeats() {
        assertEquals(listOf("3840x2160", "1920x1440", "1920x1080"), CameraReportText.sizes(listOf(1920 to 1080, 3840 to 2160, 1920 to 1440, 1920 to 1080)))
    }

    @Test
    fun longVendorArraysAreCut() {
        val text = CameraReportText.values((1..CameraReportText.MAX_VALUES + 5).toList())
        assertTrue(text.endsWith(",… (${CameraReportText.MAX_VALUES + 5} values)"))
        assertEquals("1,2,3", CameraReportText.values(listOf(1, 2, 3)))
    }

    @Test
    fun theReportListsEachCameraWithItsSizesAndVendorTables() {
        val text = CameraReportText.format(
            listOf(
                CameraReportEntry(
                    id = "1",
                    origin = "listed",
                    facing = "FRONT",
                    pixelArray = "6560x4928",
                    activeArray = "6560x4928",
                    sizes = mapOf("PRIVATE" to listOf("1920x1440", "1920x1080"), "YUV_420_888" to listOf("6560x4928")),
                    maxResolutionSizes = emptyMap(),
                    dynamicRangeProfiles = listOf("STANDARD"),
                    vendorValues = mapOf("xiaomi.scaler.availableLimitStreamConfigurations" to "34,3840,2160,0")
                )
            )
        )
        assertEquals(
            """
            camera 1 (listed) FRONT
              pixel array 6560x4928
              active array 6560x4928
              dynamic range STANDARD
              PRIVATE 1920x1440, 1920x1080
              YUV_420_888 6560x4928
              xiaomi.scaler.availableLimitStreamConfigurations = 34,3840,2160,0
            """.trimIndent(),
            text
        )
    }

    @Test
    fun vendorValuesOfEveryTypeBecomeText() {
        assertEquals("34,3840,2160,0", CameraReportText.describe(intArrayOf(34, 3840, 2160, 0)))
        assertEquals("1,2", CameraReportText.describe(longArrayOf(1, 2)))
        assertEquals("1.5", CameraReportText.describe(floatArrayOf(1.5f)))
        assertEquals("7", CameraReportText.describe(byteArrayOf(7)))
        assertEquals("3", CameraReportText.describe(3))
        assertEquals("4", CameraReportText.describe(4L))
        assertEquals(null, CameraReportText.describe(null))
        assertEquals(null, CameraReportText.describe("text"))
    }

    @Test
    fun idsAreListedThenTheirPhysicalCamerasThenUnlistedOnesThatAnswer() {
        val physical = mapOf("0" to listOf("2", "3"), "1" to listOf("4", "0"))
        val ids = CameraReportText.ids(listOf("0", "1"), { physical[it].orEmpty() }, probeUpTo = 8) { it == "6" || it == "2" }
        assertEquals(
            listOf("0" to "listed", "1" to "listed", "2" to "physical of 0", "3" to "physical of 0", "4" to "physical of 1", "6" to "unlisted"),
            ids
        )
        assertEquals(listOf("0" to "listed"), CameraReportText.ids(listOf("0"), { emptyList() }, probeUpTo = 0) { true })
    }

    @Test
    fun theSizeTableLeavesOutEmptyFormats() {
        assertEquals(
            mapOf("PRIVATE" to listOf("1920x1440", "1920x1080")),
            CameraReportText.sizeTable(listOf("PRIVATE" to listOf(1920 to 1080, 1920 to 1440), "RAW_SENSOR" to emptyList()))
        )
    }
}
