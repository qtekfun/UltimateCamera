package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitPrefsTest {
    @Test
    fun portraitIsOffByDefault() {
        assertFalse(DataStorePortraitRepository.read(mutablePreferencesOf()))
    }

    @Test
    fun aStoredSwitchReadsBack() {
        assertTrue(DataStorePortraitRepository.read(mutablePreferencesOf(DataStorePortraitRepository.PORTRAIT to true)))
        assertFalse(DataStorePortraitRepository.read(mutablePreferencesOf(DataStorePortraitRepository.PORTRAIT to false)))
    }

    @Test
    fun aValueOfTheWrongTypeIsOff() {
        assertFalse(DataStorePortraitRepository.read(mutablePreferencesOf(stringPreferencesKey("photo_portrait") to "yes")))
    }
}
