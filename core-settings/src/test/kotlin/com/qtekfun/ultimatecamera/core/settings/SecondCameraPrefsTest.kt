package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondCameraPrefsTest {
    @Test
    fun automaticByDefault() {
        assertNull(SecondCameraKeys.read(mutablePreferencesOf()))
    }

    @Test
    fun aChosenPairReadsBackAndNullClearsIt() {
        val stored = mutablePreferencesOf().also { SecondCameraKeys.write(it, "0,2") }
        assertEquals("0,2", SecondCameraKeys.read(stored))
        SecondCameraKeys.write(stored, null)
        assertNull(SecondCameraKeys.read(stored))
    }

    @Test
    fun aValueOfTheWrongTypeIsIgnored() {
        assertNull(SecondCameraKeys.read(mutablePreferencesOf(booleanPreferencesKey("second_camera_pair") to true)))
    }
}
