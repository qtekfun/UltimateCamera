package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RoleSourcesTest {
    @After
    fun restore() {
        RoleSources.current = RoleSources.Standalone
    }

    @Test
    fun everyPhoneIsStandaloneUntilTheAppSetsASource() = runBlocking {
        val standalone = RoleSources.current
        assertSame(RoleSources.Standalone, standalone)
        assertEquals(listOf<DeviceRole?>(null), standalone.role.toList())
        assertNull(standalone.pairingToken())
        assertNull(standalone.deviceName())
        assertNull("the camera keeps its own plain pairings file", standalone.pairings())
    }

    @Test
    fun theSourceSetAtStartIsTheOneTheCameraReads() = runBlocking {
        val camera = object : RoleSource {
            override val role: Flow<DeviceRole?> = flowOf(DeviceRole.CAMERA)

            override fun deviceName() = "B-cam"
        }
        RoleSources.current = camera
        assertEquals(listOf<DeviceRole?>(DeviceRole.CAMERA), RoleSources.current.role.toList())
        assertEquals("B-cam", RoleSources.current.deviceName())
        assertNull(RoleSources.current.pairingToken())
    }
}
