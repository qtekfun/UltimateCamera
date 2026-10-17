package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.gpu.meters.Desqueeze
import com.qtekfun.ultimatecamera.core.gpu.meters.GuideSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeKind
import com.qtekfun.ultimatecamera.core.settings.DesqueezeFactor
import com.qtekfun.ultimatecamera.core.settings.FrameLines
import com.qtekfun.ultimatecamera.core.settings.MonitorPrefs
import com.qtekfun.ultimatecamera.core.settings.MonitorScope
import com.qtekfun.ultimatecamera.core.settings.PeakingColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorMappingTest {
    @Test
    fun defaultsDrawNothing() {
        assertFalse(MonitorMapping.toSettings(MonitorPrefs.DEFAULT).active)
    }

    @Test
    fun disabledMetersGroupTurnsEverythingOff() {
        val prefs = MonitorPrefs(zebras = true, peaking = true, scope = MonitorScope.HISTOGRAM)
        assertEquals(MonitorSettings.OFF, MonitorMapping.toSettings(MonitorUiState(available = false, prefs = prefs)))
        assertTrue(MonitorMapping.toSettings(MonitorUiState(available = true, prefs = prefs)).active)
    }

    @Test
    fun choicesMapToTheRendererSettings() {
        val prefs = MonitorPrefs(
            zebras = true,
            zebraLevel = 90,
            skinZebras = true,
            falseColor = true,
            peaking = true,
            peakingColor = PeakingColor.BLUE,
            scope = MonitorScope.VECTORSCOPE,
            frameLines = FrameLines.FLAT_1_85,
            thirds = true,
            centre = true,
            desqueeze = DesqueezeFactor.X1_5
        )
        val s = MonitorMapping.toSettings(prefs)
        val o = s.overlay
        assertTrue(o.zebras.primary.enabled)
        assertEquals(90f, o.zebras.primary.minPercent)
        assertTrue("clipped white must be striped", o.zebras.primary.maxPercent > 100f)
        assertTrue(o.zebras.secondary.enabled)
        assertEquals(65f, o.zebras.secondary.minPercent)
        assertEquals(75f, o.zebras.secondary.maxPercent)
        assertTrue(o.falseColor.enabled)
        assertTrue(o.peaking.enabled)
        assertEquals(PeakingColor.BLUE.argb, o.peaking.color)
        assertEquals(GuideSettings.ASPECT_1_85, o.guides.frameLineAspect)
        assertTrue(o.guides.thirds && o.guides.centre)
        assertEquals(Desqueeze.X1_5, o.desqueeze)
        assertEquals(ScopeKind.VECTORSCOPE, s.scope)
    }

    @Test
    fun everyChoiceHasARendererCounterpart() {
        DesqueezeFactor.entries.forEach { f ->
            assertEquals(f.factor, MonitorMapping.toSettings(MonitorPrefs(desqueeze = f)).overlay.desqueeze.factor)
        }
        MonitorScope.entries.forEach { scope ->
            assertEquals(scope.name, MonitorMapping.toSettings(MonitorPrefs(scope = scope)).scope.name)
        }
        FrameLines.entries.forEach { lines ->
            assertEquals(lines.aspect, MonitorMapping.toSettings(MonitorPrefs(frameLines = lines)).overlay.guides.frameLineAspect)
        }
    }
}
