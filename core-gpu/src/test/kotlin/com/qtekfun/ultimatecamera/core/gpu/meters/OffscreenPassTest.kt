package com.qtekfun.ultimatecamera.core.gpu.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D68: the screen-sized offscreen pass runs only when something needs it. */
class OffscreenPassTest {
    @Test
    fun offNeverNeedsTheOffscreenPass() {
        (0L until 16L).forEach { assertFalse(MonitorSettings.OFF.needsOffscreenPass(it)) }
    }

    @Test
    fun anOverlayNeedsItEveryFrame() {
        val s = MonitorSettings(overlay = MonitorOverlaySettings(peaking = PeakingSettings(enabled = true)), scope = ScopeKind.WAVEFORM)
        (0L until 16L).forEach { assertTrue(s.needsOffscreenPass(it)) }
    }

    @Test
    fun aScopeAloneNeedsItOnlyOnItsAnalysisFrames() {
        val s = MonitorSettings(scope = ScopeKind.VECTORSCOPE)
        val frames = (0L until 16L).filter(s::needsOffscreenPass)
        assertEquals(listOf(0L, 4L, 8L, 12L), frames)
    }

    @Test
    fun aLowerScopeRateThinsTheAnalysisAndTheOffscreenPass() {
        val s = MonitorSettings(scope = ScopeKind.HISTOGRAM, scopeUpdateEvery = 8)
        assertEquals(8, s.scopeSettings().updateEveryFrames)
        assertEquals(listOf(0L, 8L), (0L until 16L).filter(s::needsOffscreenPass))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aZeroScopeRateIsRefused() {
        MonitorSettings(scopeUpdateEvery = 0)
    }
}
