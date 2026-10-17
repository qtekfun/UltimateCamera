package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class StillQualityTest {
    @Test
    fun highQualityModesAreUsedOnlyWhenListed() {
        val all = StillQuality.from(
            intArrayOf(CameraMetadata.TONEMAP_MODE_FAST, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY),
            intArrayOf(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY),
            intArrayOf(CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        )
        assertEquals(Triple(true, true, true), all.modes)
        val limited = StillQuality.from(intArrayOf(CameraMetadata.TONEMAP_MODE_FAST), null, intArrayOf(CameraMetadata.EDGE_MODE_FAST))
        assertEquals(Triple(false, false, false), limited.modes)
    }
}
