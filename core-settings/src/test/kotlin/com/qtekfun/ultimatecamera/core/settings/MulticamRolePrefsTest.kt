package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MulticamRolePrefsTest {
    @Test
    fun noStoredRoleMeansMulticamOff() {
        assertNull(MulticamRoleKeys.read(mutablePreferencesOf()))
    }

    @Test
    fun everyRoleRoundTrips() {
        MulticamRole.entries.forEach { role ->
            val stored = mutablePreferencesOf().also { MulticamRoleKeys.write(it, role) }
            assertEquals(role, MulticamRoleKeys.read(stored))
        }
    }

    @Test
    fun clearingTheRoleRemovesTheKey() {
        val stored = mutablePreferencesOf().also {
            MulticamRoleKeys.write(it, MulticamRole.MASTER)
            MulticamRoleKeys.write(it, null)
        }
        assertNull(stored[MulticamRoleKeys.ROLE])
    }

    @Test
    fun anUnknownStoredValueIsOff() {
        assertNull(MulticamRoleKeys.read(mutablePreferencesOf(MulticamRoleKeys.ROLE to "director")))
    }

    @Test
    fun persistedKeysNeverChange() {
        assertEquals(listOf("camera", "monitor", "master"), MulticamRole.entries.map { it.key })
    }
}
