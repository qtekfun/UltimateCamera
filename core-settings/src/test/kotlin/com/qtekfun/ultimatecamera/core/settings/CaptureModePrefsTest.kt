package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureModePrefsTest {
    @Test
    fun defaultsOpenTheLastModeWhichStartsAsVideo() {
        val prefs = CaptureModeKeys.read(mutablePreferencesOf())
        assertEquals(StartMode.LAST_USED, prefs.startMode)
        assertEquals(ShutterMode.VIDEO, prefs.modeAtStart)
    }

    @Test
    fun lastUsedFollowsTheStoredMode() {
        val stored = mutablePreferencesOf().also { CaptureModeKeys.writeLast(it, ShutterMode.PHOTO) }
        assertEquals(ShutterMode.PHOTO, CaptureModeKeys.read(stored).modeAtStart)
    }

    @Test
    fun aFixedStartModeWinsOverTheLastOne() {
        val stored = mutablePreferencesOf().also {
            CaptureModeKeys.writeLast(it, ShutterMode.PHOTO)
            CaptureModeKeys.writeStart(it, StartMode.VIDEO)
        }
        assertEquals(ShutterMode.VIDEO, CaptureModeKeys.read(stored).modeAtStart)
    }

    @Test
    fun timecodeDefaultsToRecordRunAndKeepsTimeOfDay() {
        assertEquals(TimecodeMode.RECORD_RUN, CaptureModeKeys.read(mutablePreferencesOf()).timecode)
        val stored = mutablePreferencesOf().also { CaptureModeKeys.writeTimecode(it, TimecodeMode.TIME_OF_DAY) }
        assertEquals(TimecodeMode.TIME_OF_DAY, CaptureModeKeys.read(stored).timecode)
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        val stored = mutablePreferencesOf(
            CaptureModeKeys.START_MODE to "cinema",
            CaptureModeKeys.LAST_MODE to "slowmo",
            CaptureModeKeys.TIMECODE_MODE to "free_run"
        )
        assertEquals(CaptureModePrefs(), CaptureModeKeys.read(stored))
    }
}
