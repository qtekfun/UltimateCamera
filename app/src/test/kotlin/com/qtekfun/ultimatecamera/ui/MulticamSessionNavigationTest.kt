package com.qtekfun.ultimatecamera.ui

import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import com.qtekfun.ultimatecamera.feature.multicam.session.RoleAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MulticamSessionNavigationTest {
    @Test
    fun aPhoneMadeTheCameraGoesToTheCameraScreenWhereItsRoleRuns() {
        // Before D80 the camera phone stayed on the multicamera screen, where no control server runs: no status, no video.
        assertEquals(Destination.CAMERA, MulticamSessionNavigation.destination(RoleAssignment(MulticamRole.CAMERA, "p", fromPeer = true)))
    }

    @Test
    fun aPhoneMadeTheMonitorOpensTheMulticamScreen() {
        assertEquals(Destination.MULTICAM, MulticamSessionNavigation.destination(RoleAssignment(MulticamRole.MONITOR, "p", fromPeer = true)))
        assertEquals(Destination.MULTICAM, MulticamSessionNavigation.destination(RoleAssignment(MulticamRole.MASTER, "p", fromPeer = true)))
    }

    @Test
    fun anEndedSessionOrALocalChoiceStaysPut() {
        assertNull(MulticamSessionNavigation.destination(RoleAssignment(null, "p", fromPeer = true)))
        assertNull(MulticamSessionNavigation.destination(RoleAssignment(MulticamRole.CAMERA, "p", fromPeer = false)))
    }
}
