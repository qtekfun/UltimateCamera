package com.qtekfun.ultimatecamera.core.gpu.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorSettingsTest {
    @Test
    fun offDrawsThePlainPreview() {
        assertFalse(MonitorSettings.OFF.active)
        assertFalse(MonitorSettings.OFF.scopeSettings().anyEnabled)
    }

    @Test
    fun everyOverlayActivatesTheMonitorPass() {
        val base = MonitorOverlaySettings()
        val variants = listOf(
            base.copy(peaking = PeakingSettings(enabled = true)),
            base.copy(zebras = ZebraSettings(primary = ZebraSettings().primary.copy(enabled = true))),
            base.copy(zebras = ZebraSettings(secondary = ZebraSettings().secondary.copy(enabled = true))),
            base.copy(falseColor = FalseColorSettings(enabled = true)),
            base.copy(guides = GuideSettings(thirds = true)),
            base.copy(guides = GuideSettings(centre = true)),
            base.copy(guides = GuideSettings(frameLineAspect = GuideSettings.ASPECT_2_39)),
            base.copy(desqueeze = Desqueeze.X2)
        )
        variants.forEach { assertTrue("$it", MonitorSettings(overlay = it).overlayActive) }
    }

    @Test
    fun onlyTheShownScopeIsAnalysedAtReducedRate() {
        val s = MonitorSettings(scope = ScopeKind.VECTORSCOPE)
        assertTrue(s.active)
        assertFalse(s.overlayActive)
        val scopes = s.scopeSettings()
        assertTrue(scopes.vectorscope)
        assertFalse(scopes.histogram || scopes.waveform)
        assertEquals(MonitorSettings.SCOPE_UPDATE_EVERY, scopes.updateEveryFrames)
        assertEquals(4, scopes.updateEveryFrames)
    }

    @Test
    fun scopePanelSitsBottomLeftInsideTheImage() {
        val content = PixelRect(120, 0, 1920, 1080)
        val r = assertNotNullRect(ScopePanelLayout.rect(ScopeKind.WAVEFORM, 2160, 1080, content))
        assertTrue(r.x >= content.x && r.y >= content.y)
        assertTrue(r.right <= 2160 && r.top <= 1080)
        assertEquals(1.6f, r.width.toFloat() / r.height, 0.02f)
        val v = assertNotNullRect(ScopePanelLayout.rect(ScopeKind.VECTORSCOPE, 2160, 1080, content))
        assertEquals(v.width, v.height)
        assertTrue(v.height <= 1080 * ScopePanelLayout.MAX_HEIGHT_FRACTION)
    }

    @Test
    fun portraitPanelIsWiderAndAboveTheBottomControls() {
        // A 16:9 video turned to 9:16 inside a 1080x2400 portrait surface.
        val content = PixelRect(0, 240, 1080, 1920)
        val r = assertNotNullRect(ScopePanelLayout.rect(ScopeKind.HISTOGRAM, 1080, 2400, content))
        assertTrue(r.width >= (1080 * ScopePanelLayout.PORTRAIT_WIDTH_FRACTION).toInt() - 1)
        assertTrue(r.y >= 2400 * ScopePanelLayout.PORTRAIT_BOTTOM_FRACTION - 1)
        assertTrue(r.right <= 1080 && r.top <= 2400)
    }

    @Test
    fun noPanelWithoutScopeOrRoom() {
        assertNull(ScopePanelLayout.rect(ScopeKind.NONE, 2160, 1080, PixelRect(0, 0, 2160, 1080)))
        assertNull(ScopePanelLayout.rect(ScopeKind.HISTOGRAM, 64, 64, PixelRect(0, 0, 64, 64)))
    }

    private fun assertNotNullRect(r: PixelRect?): PixelRect {
        assertNotNull(r)
        return r!!
    }
}
