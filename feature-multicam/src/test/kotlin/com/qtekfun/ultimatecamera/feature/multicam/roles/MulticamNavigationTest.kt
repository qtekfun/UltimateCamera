package com.qtekfun.ultimatecamera.feature.multicam.roles

import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import org.junit.Assert.assertEquals
import org.junit.Test

class MulticamNavigationTest {
    @Test
    fun theCameraLeavesForTheCameraScreenAndTheMonitorOpensItsControls() {
        // The camera role (control server, status, video) only runs on the camera screen (D56).
        assertEquals(MulticamNavigation.Target.Camera, MulticamNavigation.afterRoles(MulticamRole.CAMERA))
        assertEquals(MulticamNavigation.Target.Control, MulticamNavigation.afterRoles(MulticamRole.MONITOR))
        assertEquals(MulticamNavigation.Target.Control, MulticamNavigation.afterRoles(MulticamRole.MASTER))
        assertEquals(MulticamNavigation.Target.Stay, MulticamNavigation.afterRoles(null))
    }
}
