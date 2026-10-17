package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class MulticamPrefsTest {
    @Test
    fun bothOptionsAreOffByDefault() {
        assertEquals(MulticamPrefs(recordProxy = false, concurrentCamera = false), MulticamKeys.read(mutablePreferencesOf()))
    }

    @Test
    fun writtenValuesReadBack() {
        val prefs = MulticamPrefs(recordProxy = true, concurrentCamera = true)
        val stored = mutablePreferencesOf().also { MulticamKeys.write(it, prefs) }
        assertEquals(prefs, MulticamKeys.read(stored))
    }

    @Test
    fun aValueOfTheWrongTypeIsIgnored() {
        val stored = mutablePreferencesOf(stringPreferencesKey("record_proxy") to "yes")
        assertEquals(MulticamPrefs(), MulticamKeys.read(stored))
    }
}
