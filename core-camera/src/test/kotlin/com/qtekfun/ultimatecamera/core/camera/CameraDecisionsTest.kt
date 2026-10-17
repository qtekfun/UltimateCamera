package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions behind CameraStream, StillCaptureStream, PhotoHdrProbe, LensReader and the capability probe. */
class CameraDecisionsTest {
    private val focusing = ControlRanges(minFocusDiopters = 10f)

    @Test
    fun afTriggerOnlyWhenLockOrPointChangesWithAutofocus() {
        val start = CameraControls()
        assertNull("nothing changed", CameraStream.afTrigger(start, start, focusing))
        assertNull("first request without lock or point", CameraStream.afTrigger(null, start, focusing))
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_START, CameraStream.afTrigger(start, start.copy(afLock = true), focusing))
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, CameraStream.afTrigger(start.copy(afLock = true), start, focusing))
        val tap = start.copy(meteringPoint = ImagePoint(0.2f, 0.8f))
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL, CameraStream.afTrigger(start, tap, focusing))
        assertEquals(CameraMetadata.CONTROL_AF_TRIGGER_START, CameraStream.afTrigger(start, tap.copy(afLock = true), focusing))
        // Other controls never trigger AF.
        assertNull(CameraStream.afTrigger(start, start.copy(iso = 800, zoomRatio = 2f), focusing))
    }

    @Test
    fun noAfTriggerWithManualOrFixedFocus() {
        val start = CameraControls()
        val lock = start.copy(afLock = true)
        assertNull(CameraStream.afTrigger(start, lock.copy(manualFocus = true), focusing))
        assertNull(CameraStream.afTrigger(start, lock, ControlRanges(minFocusDiopters = 0f)))
    }

    @Test
    fun liveExposureReportsLocksFromTheStates() {
        val locked = CameraStream.liveExposure(400, 10_000_000L, 1.5f, CaptureResult.CONTROL_AE_STATE_LOCKED, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED)
        assertEquals(LiveExposure(400, 10_000_000L, 1.5f, aeLocked = true, afLocked = true), locked)
        assertTrue(CameraStream.liveExposure(null, null, null, null, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED).afLocked)
        val scanning = CameraStream.liveExposure(null, null, null, CaptureResult.CONTROL_AE_STATE_CONVERGED, CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN)
        assertFalse(scanning.aeLocked)
        assertFalse(scanning.afLocked)
        assertEquals(LiveExposure(), CameraStream.liveExposure(null, null, null, null, null))
    }

    @Test
    fun tenBitProfilePrefersHlg() {
        assertEquals(
            DynamicRangeProfiles.HLG10,
            CameraStream.tenBitProfile(setOf(DynamicRangeProfiles.STANDARD, DynamicRangeProfiles.HDR10, DynamicRangeProfiles.HLG10))
        )
        assertEquals(DynamicRangeProfiles.HDR10, CameraStream.tenBitProfile(setOf(DynamicRangeProfiles.STANDARD, DynamicRangeProfiles.HDR10)))
        assertEquals(DynamicRangeProfiles.HDR10_PLUS, CameraStream.tenBitProfile(setOf(DynamicRangeProfiles.HDR10_PLUS)))
        // Dolby Vision alone is not a profile the pipeline records.
        assertNull(CameraStream.tenBitProfile(setOf(DynamicRangeProfiles.STANDARD, DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM)))
        assertNull(CameraStream.tenBitProfile(emptySet()))
    }

    @Test
    fun precaptureWaitsForTheTriggerResultAndBothLoops() {
        val focused = CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        val converged = CaptureResult.CONTROL_AE_STATE_CONVERGED
        // A result from before the trigger never ends the wait.
        assertFalse(StillCaptureStream.precaptureDone(9, 10, focused, converged, sawPrecapture = true, autoFocus = true, precapture = true))
        assertTrue(StillCaptureStream.precaptureDone(10, 10, focused, converged, sawPrecapture = true, autoFocus = true, precapture = true))
        // AF still scanning.
        val scanning = CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
        assertFalse(StillCaptureStream.precaptureDone(12, 10, scanning, converged, sawPrecapture = true, autoFocus = true, precapture = true))
        assertTrue(StillCaptureStream.precaptureDone(12, 10, scanning, converged, sawPrecapture = true, autoFocus = false, precapture = true))
        // AE converged before its sequence ran is the old exposure: wait for PRECAPTURE or the grace frames.
        assertFalse(StillCaptureStream.precaptureDone(11, 10, focused, converged, sawPrecapture = false, autoFocus = true, precapture = true))
        val grace = 10L + StillCaptureStream.AE_GRACE_FRAMES
        assertTrue(StillCaptureStream.precaptureDone(grace, 10, focused, converged, sawPrecapture = false, autoFocus = true, precapture = true))
        assertTrue(
            StillCaptureStream.precaptureDone(11, 10, focused, CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED, true, autoFocus = true, precapture = true)
        )
        assertFalse(
            StillCaptureStream.precaptureDone(11, 10, focused, CaptureResult.CONTROL_AE_STATE_SEARCHING, true, autoFocus = true, precapture = true)
        )
        // HALs that report no states settle at once; no precapture support ignores AE.
        assertTrue(StillCaptureStream.precaptureDone(10, 10, null, null, sawPrecapture = false, autoFocus = true, precapture = true))
        assertTrue(
            StillCaptureStream.precaptureDone(10, 10, focused, CaptureResult.CONTROL_AE_STATE_SEARCHING, false, autoFocus = true, precapture = false)
        )
    }

    @Test
    fun stillAutofocusAndPrecaptureSupport() {
        val modes = setOf(CameraMetadata.CONTROL_AF_MODE_OFF, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        assertTrue(StillCaptureStream.autoFocus(modes, 10f))
        assertFalse("fixed focus", StillCaptureStream.autoFocus(modes, 0f))
        assertFalse(StillCaptureStream.autoFocus(modes, null))
        assertFalse(StillCaptureStream.autoFocus(setOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO), 10f))
        assertFalse(StillCaptureStream.autoFocus(null, 10f))
        assertFalse(StillCaptureStream.precaptureSupported(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY))
        assertTrue(StillCaptureStream.precaptureSupported(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))
        assertTrue(StillCaptureStream.precaptureSupported(null))
    }

    @Test
    fun bracketCompensationStepsNeedPlusMinusOneEv() {
        // Third stops, ±2 EV: one stop is three steps.
        assertEquals(3, PhotoHdrProbe.compensationSteps(-6..6, 1.0 / 3, 1.0))
        assertEquals(-3, PhotoHdrProbe.compensationSteps(-6..6, 1.0 / 3, -1.0))
        // Clamped to the range.
        assertEquals(6, PhotoHdrProbe.compensationSteps(-6..6, 1.0 / 3, 4.0))
        assertEquals(-4, PhotoHdrProbe.compensationSteps(-4..4, 0.5, -3.0))
        // Under ±1 EV, or a broken step: no bracket by compensation.
        assertNull(PhotoHdrProbe.compensationSteps(-2..2, 1.0 / 3, 1.0))
        assertNull(PhotoHdrProbe.compensationSteps(-6..2, 1.0 / 3, 1.0))
        assertNull(PhotoHdrProbe.compensationSteps(-6..6, 0.0, 1.0))
    }

    @Test
    fun lensFacing() {
        assertEquals(LensFacing.BACK, LensReader.facing(CameraCharacteristics.LENS_FACING_BACK))
        assertEquals(LensFacing.FRONT, LensReader.facing(CameraCharacteristics.LENS_FACING_FRONT))
        assertEquals(LensFacing.EXTERNAL, LensReader.facing(CameraCharacteristics.LENS_FACING_EXTERNAL))
        assertEquals(LensFacing.EXTERNAL, LensReader.facing(null))
    }

    @Test
    fun videoModesFromHdUpLargestFirstWithTheFastestRate() {
        val sizes = listOf(PixelSize(1920, 1080), PixelSize(640, 480), PixelSize(3840, 2160), PixelSize(1280, 720), PixelSize(1280, 960), PixelSize(7680, 4320))
        val frameNs = mapOf(0 to 8_333_333L, 2 to 16_666_666L, 3 to 4_166_666L, 4 to 33_333_333L, 5 to 0L)
        val modes = CameraCapabilityProbe.videoModes(sizes) { frameNs.getValue(it) }
        assertEquals(
            listOf(
                StreamMode(7680, 4320, 0),
                StreamMode(3840, 2160, 60),
                StreamMode(1920, 1080, 120),
                StreamMode(1280, 960, 30),
                StreamMode(1280, 720, 240)
            ),
            modes
        )
        assertEquals("3840x2160@60", modes[1].toString())
        assertTrue(CameraCapabilityProbe.videoModes(emptyList()) { 0L }.isEmpty())
    }

    @Test
    fun concurrentSetsAreReportedInAStableOrder() {
        val sets = listOf(setOf("3", "1"), setOf("0", "2"), setOf("2", "0", "1"))
        assertEquals(listOf(listOf("0", "1", "2"), listOf("0", "2"), listOf("1", "3")), ConcurrentCameraProbe.orderedSets(sets))
        assertEquals(mapOf("0" to 30.0, "1" to 14.4), ConcurrentCameraProbe.frameRates(mapOf("0" to 150, "1" to 72), 5.0))
        assertTrue(ConcurrentCameraProbe.frameRates(mapOf("0" to 150), 0.0).isEmpty())
    }

    @Test
    fun opticsNeedFocalAndSensorSize() {
        assertEquals(LensOptics(6.9f, 9.8f, 7.4f), LensReader.optics(6.9f, 9.8f, 7.4f))
        assertEquals(null, LensReader.optics(null, 9.8f, 7.4f))
        assertEquals(null, LensReader.optics(6.9f, null, null))
    }

    @Test
    fun highSpeedModesListEachRangeTopOnce() {
        val modes = CameraCapabilityProbe.highSpeedModes(
            listOf(
                PixelSize(1920, 1080) to listOf(120, 240, 240),
                PixelSize(1280, 720) to listOf(480),
                PixelSize(3840, 2160) to emptyList()
            )
        )
        assertEquals(listOf("1920x1080@120", "1920x1080@240", "1280x720@480"), modes)
    }

    @Test
    fun vendorAndHdrKeysForTheCatalogue() {
        val names = listOf("com.oplus.video.hdr.mode", "android.control.aeMode", "org.codeaurora.qcamera3.HDR.enable", "com.oplus.zoom")
        assertEquals(listOf("com.oplus.video.hdr.mode", "com.oplus.zoom", "org.codeaurora.qcamera3.HDR.enable"), CameraCapabilityProbe.vendorKeys(names))
        assertEquals(
            listOf("com.oplus.video.hdr.mode", "org.codeaurora.qcamera3.HDR.enable"),
            VendorValueReader.hdrKeys(names + "android.control.hdrMode")
        )
    }
}
