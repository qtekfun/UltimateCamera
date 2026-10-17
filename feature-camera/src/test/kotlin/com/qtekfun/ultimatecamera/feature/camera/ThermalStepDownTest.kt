package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedCamera
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedMode
import com.qtekfun.ultimatecamera.core.capabilities.CameraCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.CodecType
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DeviceIdentity
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange
import com.qtekfun.ultimatecamera.core.capabilities.LutMode
import com.qtekfun.ultimatecamera.core.capabilities.ProbeOutcome
import com.qtekfun.ultimatecamera.core.capabilities.ProbeResult
import com.qtekfun.ultimatecamera.core.capabilities.VideoConfig
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** REQ-NFR-001 guided degradation (D68): offer once per take, apply only between takes. */
class ThermalStepDownTest {
    private val uhd30 = RecordingProfile(3840, 2160, 30, VideoCodec.HEVC, tenBit = true)
    private val fhd60 = RecordingProfile(1920, 1080, 60, VideoCodec.HEVC, tenBit = false)
    private val normal = PowerManager.THERMAL_STATUS_LIGHT

    @Test
    fun nothingIsOfferedWhileCoolOrNotRecording() {
        val s = ThermalStepDown()
        assertNull(s.onHealth(recording = true, normal, severeSoon = false) { fhd60 })
        assertNull(s.onHealth(recording = false, PowerManager.THERMAL_STATUS_SEVERE, severeSoon = true) { fhd60 })
        assertNull(s.takeEnded(automatic = true))
    }

    @Test
    fun aSevereForecastOffersTheNextModeOncePerTake() {
        val s = ThermalStepDown()
        assertEquals(fhd60, s.onHealth(recording = true, normal, severeSoon = true) { fhd60 })
        assertNull("offered once", s.onHealth(recording = true, normal, severeSoon = true) { fhd60 })
        assertNull("SEVERE now does not offer again", s.onHealth(recording = true, PowerManager.THERMAL_STATUS_SEVERE, severeSoon = false) { fhd60 })
    }

    @Test
    fun severeNowAlsoOffers() {
        assertEquals(fhd60, ThermalStepDown().onHealth(recording = true, PowerManager.THERMAL_STATUS_SEVERE, severeSoon = false) { fhd60 })
    }

    @Test
    fun withoutAcceptOrProtectTheModeStays() {
        val s = ThermalStepDown()
        s.onHealth(recording = true, normal, severeSoon = true) { fhd60 }
        assertNull(s.takeEnded(automatic = false))
    }

    @Test
    fun protectThePhoneAppliesAtTheEndOfTheTakeNeverDuringIt() {
        val s = ThermalStepDown()
        s.onHealth(recording = true, normal, severeSoon = true) { fhd60 }
        // Accepting nothing mid-take: still recording, no switch is returned.
        assertEquals(fhd60, s.takeEnded(automatic = true))
        assertNull("applied once", s.takeEnded(automatic = true))
    }

    @Test
    fun anAcceptedOfferWaitsForTheEndOfTheTake() {
        val s = ThermalStepDown()
        s.onHealth(recording = true, normal, severeSoon = true) { fhd60 }
        assertNull("never mid-take", s.accept())
        assertEquals(fhd60, s.takeEnded(automatic = false))
    }

    @Test
    fun acceptingAfterTheTakeEndedSwitchesAtOnce() {
        val s = ThermalStepDown()
        s.onHealth(recording = true, normal, severeSoon = true) { fhd60 }
        assertNull(s.takeEnded(automatic = false))
        s.onHealth(recording = false, normal, severeSoon = false) { fhd60 }
        assertEquals(fhd60, s.accept())
        assertNull(s.accept())
    }

    @Test
    fun aNewTakeCanBeOfferedAgain() {
        val s = ThermalStepDown()
        s.onHealth(recording = true, normal, severeSoon = true) { fhd60 }
        s.takeEnded(automatic = false)
        s.onHealth(recording = false, normal, severeSoon = true) { fhd60 }
        assertEquals(fhd60, s.onHealth(recording = true, normal, severeSoon = true) { fhd60 })
    }

    @Test
    fun theLowestModeHasNothingToOffer() {
        val s = ThermalStepDown()
        assertNull(s.onHealth(recording = true, normal, severeSoon = true) { null })
        assertNull(s.takeEnded(automatic = true))
    }

    @Test
    fun probedResultsGiveTheDegradationLadderRung() {
        // Pixel 8-like: 4K30 10-bit held, 4K60 8-bit held; from 4K30 10-bit the next lighter held rung is 1080p60.
        val caps = capabilities(
            listOf(
                result(3840, 2160, 30, tenBit = true),
                result(3840, 2160, 60, tenBit = false),
                result(1920, 1080, 60, tenBit = false),
                result(1920, 1080, 30, tenBit = false)
            )
        )
        assertEquals(fhd60, StepDownLadder.next(caps, "0", uhd30, emptyList()))
    }

    @Test
    fun withoutResultsTheScreenLadderIsUsed() {
        val uhd60 = RecordingProfile(3840, 2160, 60, VideoCodec.HEVC, tenBit = false)
        val ladder = listOf(uhd60, uhd30, fhd60, RecordingProfile(1920, 1080, 30, VideoCodec.HEVC, tenBit = false))
        assertEquals(fhd60, StepDownLadder.next(null, "0", uhd30, ladder))
        assertNull(StepDownLadder.next(null, "0", ladder.last(), ladder))
    }

    private fun result(w: Int, h: Int, fps: Int, tenBit: Boolean) = ProbeResult(
        cameraId = "0",
        config = VideoConfig(w, h, fps, CodecType.HEVC, if (tenBit) DynamicRange.HLG10 else DynamicRange.STANDARD, LutMode.HARDWARE),
        advertised = true,
        outcome = ProbeOutcome.ACCEPTED_AND_HELD
    )

    private fun capabilities(results: List<ProbeResult>) = DeviceCapabilities(
        identity = DeviceIdentity("Google", "google", "Pixel 8", "shiba", 36),
        engineVersion = 1,
        cameras = listOf(
            CameraCapabilities(
                AdvertisedCamera("0", "BACK", videoModes = listOf(AdvertisedMode(3840, 2160, 60)), tenBitProfiles = listOf(DynamicRange.HLG10)),
                results
            )
        )
    )
}
