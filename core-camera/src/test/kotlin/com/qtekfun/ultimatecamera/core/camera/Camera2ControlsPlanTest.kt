package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2ControlsPlanTest {
    private val spec = StreamSpec("0", 3840, 2160, 60)
    private val crop = SensorRect(0, 0, 4000, 3000)
    private val calibration = ColorCalibration(6504, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
    private val full = ControlRanges(
        isoRange = 50..6400,
        exposureTimeRangeNs = 10_000L..500_000_000L,
        evRange = -6..6,
        minFocusDiopters = 10f,
        manualFocus = true,
        aeLock = true,
        awbLock = true,
        maxAeRegions = 1,
        maxAfRegions = 1,
        activeArray = crop,
        zoomRange = 0.6f..10f,
        ois = true
    )

    private fun plan(c: CameraControls, ranges: ControlRanges = full, transform: Boolean = true, cal: Boolean = true) =
        Camera2Controls.plan(c, ranges, spec, crop, hasAutoTransform = transform, calibrations = if (cal) calibration to null else null)

    @Test
    fun autoExposureSendsCompensationAndLockButNoSensorValues() {
        val p = plan(CameraControls(exposureCompensation = -2, aeLock = true, iso = 800))
        assertEquals(CameraMetadata.CONTROL_AE_MODE_ON, p.aeMode)
        assertEquals(-2, p.exposureCompensation)
        assertEquals(true, p.aeLock)
        assertNull(p.iso)
        assertNull(p.exposureTimeNs)
        assertNull(p.frameDurationNs)
    }

    @Test
    fun manualExposureTurnsAeOffAndPinsTheFrameDurationToTheStreamRate() {
        val p = plan(CameraControls(manualExposure = true, iso = 800, exposureTimeNs = 8_333_333L, exposureCompensation = 3, aeLock = true))
        assertEquals(CameraMetadata.CONTROL_AE_MODE_OFF, p.aeMode)
        assertEquals(800, p.iso)
        assertEquals(8_333_333L, p.exposureTimeNs)
        assertEquals(16_666_667L, p.frameDurationNs)
        // Compensation and AE lock mean nothing with AE off: never sent.
        assertNull(p.exposureCompensation)
        assertNull(p.aeLock)
    }

    @Test
    fun locksAreOnlySentWhenTheCameraOffersThem() {
        val p = plan(CameraControls(aeLock = true, awbLock = true), full.copy(aeLock = false, awbLock = false))
        assertNull(p.aeLock)
        assertNull(p.awbLock)
    }

    @Test
    fun focusModeFollowsTheLens() {
        assertEquals(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, plan(CameraControls()).afMode)
        val manual = plan(CameraControls(manualFocus = true, focusDiopters = 2.5f))
        assertEquals(CameraMetadata.CONTROL_AF_MODE_OFF, manual.afMode)
        assertEquals(2.5f, manual.focusDiopters)
        // A fixed-focus lens gets no AF mode at all.
        val fixed = plan(CameraControls(), full.copy(minFocusDiopters = 0f))
        assertNull(fixed.afMode)
        assertNull(fixed.focusDiopters)
    }

    @Test
    fun aTapMetersAndFocusesUnlessFocusIsManual() {
        val tap = CameraControls(meteringPoint = ImagePoint(0.5f, 0.5f))
        val p = plan(tap)
        val region = requireNotNull(p.aeRegion)
        assertEquals(region, p.afRegion)
        // Centred on the crop region's centre.
        assertEquals(2000, region.left + region.width / 2, 1)
        assertEquals(1500, region.top + region.height / 2, 1)
        val manual = plan(tap.copy(manualFocus = true))
        assertNotNull(manual.aeRegion)
        assertNull(manual.afRegion)
        val noRegions = plan(tap, full.copy(maxAeRegions = 0, maxAfRegions = 0))
        assertNull(noRegions.aeRegion)
        assertNull(noRegions.afRegion)
        assertNull(plan(CameraControls()).aeRegion)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("$actual not within $tolerance of $expected", kotlin.math.abs(expected - actual) <= tolerance)

    @Test
    fun manualKelvinUsesGainsWithTheAutomaticMatrix() {
        val p = plan(CameraControls(whiteBalance = WhiteBalance.MANUAL, kelvin = 3200, awbLock = true))
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_OFF, p.awbMode)
        val gains = requireNotNull(p.manualGains)
        // Warmer light than the calibration: blue is boosted over red.
        assertTrue(gains.blue > gains.red)
        assertNull("AWB lock is meaningless with AWB off", p.awbLock)
    }

    @Test
    fun manualKelvinFallsBackToAutoWithoutMatrixOrCalibration() {
        val noMatrix = plan(CameraControls(whiteBalance = WhiteBalance.MANUAL), transform = false)
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_AUTO, noMatrix.awbMode)
        assertNull(noMatrix.manualGains)
        val noCalibration = plan(CameraControls(whiteBalance = WhiteBalance.MANUAL), cal = false)
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_AUTO, noCalibration.awbMode)
        assertNull(noCalibration.manualGains)
    }

    @Test
    fun presetsMapToTheirAwbModes() {
        val expected = mapOf(
            WhiteBalance.AUTO to CameraMetadata.CONTROL_AWB_MODE_AUTO,
            WhiteBalance.DAYLIGHT to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
            WhiteBalance.CLOUDY to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            WhiteBalance.TUNGSTEN to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
            WhiteBalance.FLUORESCENT to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        )
        expected.forEach { (wb, mode) ->
            val p = plan(CameraControls(whiteBalance = wb, awbLock = true))
            assertEquals(wb.name, mode, p.awbMode)
            assertEquals(true, p.awbLock)
            assertNull(p.manualGains)
        }
    }

    @Test
    fun zoomAndOisOnlyWhereTheCameraHasThem() {
        val p = plan(CameraControls(zoomRatio = 2f, ois = false))
        assertEquals(2f, p.zoomRatio)
        assertEquals(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF, p.oisMode)
        assertEquals(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON, plan(CameraControls(ois = true)).oisMode)
        val bare = plan(CameraControls(zoomRatio = 2f), full.copy(zoomRange = 1f..1f, ois = false))
        assertNull(bare.zoomRatio)
        assertNull(bare.oisMode)
    }

    @Test
    fun rangesWithoutManualSensorHideExposureAndManualFocus() {
        val raw = ControlCharacteristics(
            capabilities = setOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE),
            isoRange = 100..3200,
            exposureTimeRangeNs = 1_000L..100_000_000L,
            minFocusDiopters = 8f,
            afModes = setOf(CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        )
        val r = Camera2Controls.ranges(raw)
        assertNull(r.isoRange)
        assertNull(r.exposureTimeRangeNs)
        assertFalse(r.manualFocus)
        assertEquals(8f, r.minFocusDiopters)
        // Defaults when the camera reports nothing.
        assertEquals(0..0, r.evRange)
        assertEquals(1f / 3, r.evStep)
        assertEquals(SensorRect(0, 0, 1, 1), r.activeArray)
        assertEquals(1f..1f, r.zoomRange)
        assertEquals(setOf(WhiteBalance.AUTO), r.whiteBalances)
        assertNull(r.kelvinRange)
        assertFalse(r.ois)
        assertEquals(setOf(VideoStabilization.OFF), r.stabilizations)
    }

    @Test
    fun rangesOfAFullCamera() {
        val raw = ControlCharacteristics(
            capabilities = setOf(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
            ),
            isoRange = 50..6400,
            exposureTimeRangeNs = 10_000L..1_000_000_000L,
            evRange = -12..12,
            evStep = 1f / 6,
            minFocusDiopters = 10f,
            afModes = setOf(CameraMetadata.CONTROL_AF_MODE_OFF),
            awbModes = setOf(CameraMetadata.CONTROL_AWB_MODE_OFF, CameraMetadata.CONTROL_AWB_MODE_AUTO, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
            activeArray = SensorRect(0, 0, 4080, 3072),
            hasCalibration = true,
            aeLockAvailable = true,
            awbLockAvailable = true,
            maxAeRegions = 1,
            maxAfRegions = 2,
            zoomRange = 0.5f..8f,
            oisModes = setOf(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
            stabilizationModes = setOf(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            )
        )
        val r = Camera2Controls.ranges(raw)
        assertEquals(50..6400, r.isoRange)
        assertTrue(r.manualExposure)
        assertTrue(r.manualFocus)
        assertEquals(-12..12, r.evRange)
        assertEquals(setOf(WhiteBalance.AUTO, WhiteBalance.DAYLIGHT), r.whiteBalances)
        assertEquals(2000..10000, r.kelvinRange)
        assertTrue(r.aeLock && r.awbLock)
        assertEquals(2, r.maxAfRegions)
        assertEquals(SensorRect(0, 0, 4080, 3072), r.activeArray)
        assertTrue(r.zoomable)
        assertTrue(r.ois)
        assertEquals(setOf(VideoStabilization.OFF, VideoStabilization.ON, VideoStabilization.PREVIEW), r.stabilizations)
    }

    @Test
    fun kelvinNeedsPostProcessingAwbOffAndACalibration() {
        val base = ControlCharacteristics(
            capabilities = setOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
            awbModes = setOf(CameraMetadata.CONTROL_AWB_MODE_OFF),
            hasCalibration = true
        )
        assertNotNull(Camera2Controls.ranges(base).kelvinRange)
        assertNull(Camera2Controls.ranges(base.copy(capabilities = emptySet())).kelvinRange)
        assertNull(Camera2Controls.ranges(base.copy(awbModes = setOf(CameraMetadata.CONTROL_AWB_MODE_AUTO))).kelvinRange)
        assertNull(Camera2Controls.ranges(base.copy(hasCalibration = false)).kelvinRange)
    }

    @Test
    fun stabilizationModeRoundTrips() {
        VideoStabilization.entries.forEach { mode ->
            assertEquals(setOf(mode, VideoStabilization.OFF), Camera2Controls.stabilizations(setOf(Camera2Controls.stabilizationMode(mode))))
        }
        assertEquals(setOf(VideoStabilization.OFF), Camera2Controls.stabilizations(setOf(99)))
    }
}
