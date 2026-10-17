package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class HdrPrefsTest {
    @Test
    fun hdrViewfinderOnAndExperimentalOffByDefault() {
        assertEquals(HdrPrefs(hdrViewfinder = true, experimental = false, sensorHdr = false), HdrKeys.read(mutablePreferencesOf()))
    }

    @Test
    fun writtenValuesReadBack() {
        val prefs = HdrPrefs(hdrViewfinder = false, experimental = true, sensorHdr = true)
        val stored = mutablePreferencesOf().also { HdrKeys.write(it, prefs) }
        assertEquals(prefs, HdrKeys.read(stored))
    }

    @Test
    fun aValueOfTheWrongTypeIsIgnored() {
        val stored = mutablePreferencesOf(stringPreferencesKey("hdr_viewfinder") to "no")
        assertEquals(HdrPrefs(), HdrKeys.read(stored))
    }
}
