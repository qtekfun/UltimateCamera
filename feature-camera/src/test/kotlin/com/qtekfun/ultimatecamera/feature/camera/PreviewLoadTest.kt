package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorOverlaySettings
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.PeakingSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeKind
import com.qtekfun.ultimatecamera.core.recording.PhoneTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D68: the low-end profile and the preview cost reduction when hot. */
class PreviewLoadTest {
    private val cool = PowerManager.THERMAL_STATUS_LIGHT

    @Test
    fun aCapablePhoneKeepsTheFullPreview() {
        assertEquals(PreviewLoad.FULL, PreviewLoadPolicy.load(PhoneTier.CAPABLE, cool, severeSoon = false))
    }

    @Test
    fun aModestPhoneDefaultsToALighterPreview() {
        val load = PreviewLoadPolicy.load(PhoneTier.MODEST, cool, severeSoon = false)
        assertEquals(1, load.everyNthFrame)
        assertEquals(1280, load.maxLongSide)
        assertEquals(8, load.scopeUpdateEvery)
        assertTrue(load.scopes)
    }

    @Test
    fun heatLowersRateAndSizeAndHidesTheScope() {
        for (tier in PhoneTier.entries) {
            listOf(
                PreviewLoadPolicy.load(tier, PowerManager.THERMAL_STATUS_MODERATE, severeSoon = false),
                PreviewLoadPolicy.load(tier, cool, severeSoon = true)
            ).forEach { load ->
                assertEquals(2, load.everyNthFrame)
                assertEquals(1280, load.maxLongSide)
                assertFalse(load.scopes)
            }
        }
    }

    @Test
    fun theLoadHidesOrThinsTheScopeButKeepsTheOverlays() {
        val peaking = MonitorOverlaySettings(peaking = PeakingSettings(enabled = true))
        val asked = MonitorSettings(overlay = peaking, scope = ScopeKind.WAVEFORM)
        val hot = PreviewLoadPolicy.load(PhoneTier.CAPABLE, PowerManager.THERMAL_STATUS_SEVERE, severeSoon = true).applyTo(asked)
        assertEquals(ScopeKind.NONE, hot.scope)
        assertEquals(peaking, hot.overlay)
        val modest = PreviewLoadPolicy.load(PhoneTier.MODEST, cool, severeSoon = false).applyTo(asked)
        assertEquals(ScopeKind.WAVEFORM, modest.scope)
        assertEquals(8, modest.scopeSettings().updateEveryFrames)
        assertEquals(asked, PreviewLoad.FULL.applyTo(asked))
    }

    @Test
    fun aThermalAlertMeansSevereSoon() {
        assertFalse(PreviewLoadPolicy.severeSoon(HealthState()))
        val battery = HealthState(alerts = listOf(HealthAlert(HealthKind.BATTERY, AlertLevel.WARNING, 8)))
        assertFalse(PreviewLoadPolicy.severeSoon(battery))
        assertTrue(PreviewLoadPolicy.severeSoon(HealthState(alerts = listOf(HealthAlert(HealthKind.THERMAL, AlertLevel.WARNING, 4)))))
    }

    @Test
    fun thePreviewSurfaceIsCappedOnTheLongSide() {
        assertEquals(1280 to 576, PreviewSizeCap.cap(2400, 1080, 1280))
        assertEquals(576 to 1280, PreviewSizeCap.cap(1080, 2400, 1280))
        assertNull("already small enough", PreviewSizeCap.cap(1280, 720, 1280))
        assertNull("no cap", PreviewSizeCap.cap(2400, 1080, 0))
        assertNull("not laid out", PreviewSizeCap.cap(0, 0, 1280))
    }
}
