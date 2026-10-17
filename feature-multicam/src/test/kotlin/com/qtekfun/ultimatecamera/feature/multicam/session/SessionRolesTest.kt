package com.qtekfun.ultimatecamera.feature.multicam.session

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRolesTest {
    private val neutral = SessionRoles.NEUTRAL

    @Test
    fun relationFollowsOwnRoleAndStoredRole() {
        assertEquals(Relation.NONE, SessionRoles.relation(null, DeviceRole.CAMERA))
        assertEquals(Relation.NONE, SessionRoles.relation(null, neutral))
        assertEquals(Relation.CAMERA_OF_PEER, SessionRoles.relation(MulticamRole.CAMERA, DeviceRole.MONITOR))
        assertEquals(Relation.CAMERA_OF_PEER, SessionRoles.relation(MulticamRole.CAMERA, DeviceRole.MASTER))
        assertEquals(Relation.CONTROLS_PEER, SessionRoles.relation(MulticamRole.MONITOR, DeviceRole.CAMERA))
        assertEquals(Relation.CONTROLS_PEER, SessionRoles.relation(MulticamRole.MASTER, DeviceRole.CAMERA))
        assertEquals(Relation.NONE, SessionRoles.relation(MulticamRole.MONITOR, neutral))
        assertEquals(Relation.NONE, SessionRoles.relation(MulticamRole.CAMERA, DeviceRole.CAMERA))
    }

    @Test
    fun controllerOfOneIsMonitorOfMoreIsMaster() {
        assertEquals(null, SessionRoles.controllerRole(0))
        assertEquals(MulticamRole.MONITOR, SessionRoles.controllerRole(1))
        assertEquals(MulticamRole.MASTER, SessionRoles.controllerRole(3))
    }

    @Test
    fun choosingCameraMakesThePeerTheMonitorAndDropsOldCameras() {
        val plan = SessionRoles.plan(RoleChoice.CAMERA, "b", MulticamRole.MONITOR, mapOf("b" to neutral, "c" to DeviceRole.CAMERA))
        assertEquals(MulticamRole.CAMERA, plan.own)
        assertEquals(mapOf("c" to neutral), plan.peers) // b is already stored as a controller; c is no longer a camera of this one
        assertEquals(DeviceRole.CAMERA, plan.sender)
        assertEquals(DeviceRole.MONITOR, plan.receiver)
    }

    @Test
    fun choosingMonitorControlsThePeerAndASecondCameraMakesAMaster() {
        val first = SessionRoles.plan(RoleChoice.MONITOR, "b", null, mapOf("b" to neutral))
        assertEquals(MulticamRole.MONITOR, first.own)
        assertEquals(mapOf("b" to DeviceRole.CAMERA), first.peers)
        assertEquals(DeviceRole.MONITOR, first.sender)
        assertEquals(DeviceRole.CAMERA, first.receiver)
        val second = SessionRoles.plan(RoleChoice.MONITOR, "c", MulticamRole.MONITOR, mapOf("b" to DeviceRole.CAMERA, "c" to neutral))
        assertEquals(MulticamRole.MASTER, second.own)
        assertEquals(DeviceRole.MASTER, second.sender)
    }

    @Test
    fun endingASessionStepsDownOrTurnsOff() {
        val master = SessionRoles.plan(RoleChoice.END, "c", MulticamRole.MASTER, mapOf("b" to DeviceRole.CAMERA, "c" to DeviceRole.CAMERA))
        assertEquals(MulticamRole.MONITOR, master.own)
        assertEquals(mapOf("c" to neutral), master.peers)
        assertEquals(null, master.sender)
        assertEquals(null, master.receiver)
        assertEquals(null, SessionRoles.plan(RoleChoice.END, "b", MulticamRole.CAMERA, mapOf("b" to neutral)).own)
    }

    @Test
    fun anIncomingRequestIsTheMirrorOfTheChoice() {
        // The other phone chose to be the camera: this one becomes its monitor.
        val monitor = SessionRoles.accept("a", DeviceRole.CAMERA, DeviceRole.MONITOR, null, mapOf("a" to neutral), recording = false).getOrThrow()
        assertEquals(MulticamRole.MONITOR, monitor.own)
        assertEquals(mapOf("a" to DeviceRole.CAMERA), monitor.peers)
        // The other phone chose to watch: this one becomes the camera and stores it as its master.
        val camera = SessionRoles.accept(
            "a",
            DeviceRole.MASTER,
            DeviceRole.CAMERA,
            MulticamRole.MONITOR,
            mapOf("a" to DeviceRole.CAMERA),
            recording = false
        ).getOrThrow()
        assertEquals(MulticamRole.CAMERA, camera.own)
        assertEquals(mapOf("a" to DeviceRole.MASTER), camera.peers)
        // Ended.
        assertEquals(null, SessionRoles.accept("a", null, null, MulticamRole.MONITOR, mapOf("a" to DeviceRole.CAMERA), recording = false).getOrThrow().own)
    }

    @Test
    fun nonsenseIsRefused() {
        val twoCameras = SessionRoles.accept("a", DeviceRole.CAMERA, DeviceRole.CAMERA, null, emptyMap(), recording = false)
        assertEquals(RoleRefusal.INVALID, (twoCameras.exceptionOrNull() as RoleException).reason)
        val twoMonitors = SessionRoles.accept("a", DeviceRole.MONITOR, DeviceRole.MONITOR, null, emptyMap(), recording = false)
        assertEquals(RoleRefusal.INVALID, (twoMonitors.exceptionOrNull() as RoleException).reason)
    }

    @Test
    fun aRecordingPhoneKeepsItsRole() {
        val swap = SessionRoles.accept("a", DeviceRole.CAMERA, DeviceRole.MONITOR, MulticamRole.CAMERA, mapOf("a" to DeviceRole.MONITOR), recording = true)
        assertEquals(RoleRefusal.BUSY, (swap.exceptionOrNull() as RoleException).reason)
        // A recording camera may still gain a monitor: its own role does not change.
        val more = SessionRoles.accept(
            "b",
            DeviceRole.MONITOR,
            DeviceRole.CAMERA,
            MulticamRole.CAMERA,
            mapOf("a" to DeviceRole.MONITOR, "b" to neutral),
            recording = true
        )
        assertTrue(more.isSuccess)
        assertEquals(MulticamRole.CAMERA, more.getOrThrow().own)
    }
}
