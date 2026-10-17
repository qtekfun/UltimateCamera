package com.qtekfun.ultimatecamera.feature.camera.ui

import androidx.compose.ui.semantics.LiveRegionMode
import com.qtekfun.ultimatecamera.feature.camera.AlertLevel
import com.qtekfun.ultimatecamera.feature.camera.alertLiveRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D67: spoken take time, throttling, font-scale cap, reduced motion and live-region politeness. */
class AccessibilityHelpersTest {
    @Test
    fun `REC state speaks whole seconds`() {
        assertEquals("00:01:12", spokenElapsed(72_999))
        assertEquals("01:00:05", spokenElapsed(3_605_000))
        assertEquals("00:00:00", spokenElapsed(-5))
    }

    @Test
    fun `live timecode is throttled to one change a minute`() {
        assertEquals("00:01:00", spokenElapsed(72_999, LIVE_TIMECODE_STEP_MS))
        assertEquals("00:01:00", spokenElapsed(119_999, LIVE_TIMECODE_STEP_MS))
        assertEquals("00:02:00", spokenElapsed(120_000, LIVE_TIMECODE_STEP_MS))
        // Within a minute the announced text does not change, so TalkBack has nothing new to speak.
        val announcements = (0L until 600_000L step 66L).map { spokenElapsed(it, LIVE_TIMECODE_STEP_MS) }.distinct()
        assertEquals(10, announcements.size)
    }

    @Test
    fun `numbers follow the system font scale up to the cap`() {
        assertEquals(1f, cappedFontScale(1f), 0f)
        assertEquals(1.3f, cappedFontScale(1.3f), 0f)
        assertEquals(NUMBER_FONT_SCALE_CAP, cappedFontScale(2f), 0f)
        assertEquals(0.85f, cappedFontScale(0.85f), 0f)
    }

    @Test
    fun `animations are off only at animator scale 0`() {
        assertTrue(animationsOff(0f))
        assertFalse(animationsOff(0.5f))
        assertFalse(animationsOff(1f))
    }

    @Test
    fun `critical alerts are assertive, warnings polite`() {
        assertEquals(LiveRegionMode.Assertive, alertLiveRegion(AlertLevel.CRITICAL))
        assertEquals(LiveRegionMode.Polite, alertLiveRegion(AlertLevel.WARNING))
    }
}
