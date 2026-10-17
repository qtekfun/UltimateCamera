package com.qtekfun.ultimatecamera.feature.camera.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyTest {
    private fun step(service: ServiceChange = ServiceChange.NONE, camera: CameraChange = CameraChange.NONE) = BackgroundStep(service, camera)

    @Test
    fun recStartsTheServiceAndStopEndsIt() {
        val policy = BackgroundPolicy()
        assertEquals(step(ServiceChange.START), policy.recordingChanged(true))
        assertTrue(policy.serviceRunning)
        assertEquals(step(ServiceChange.STOP), policy.recordingChanged(false))
        assertFalse(policy.serviceRunning)
    }

    @Test
    fun aTakeIsKeptOffScreenAndResumedWithoutReopening() {
        val policy = BackgroundPolicy()
        policy.recordingChanged(true)
        assertEquals(step(camera = CameraChange.KEEP), policy.hidden())
        assertTrue(policy.serviceRunning)
        assertEquals(step(camera = CameraChange.RESUME), policy.shown())
        assertTrue(policy.serviceRunning)
    }

    @Test
    fun stoppingOffScreenStopsTheServiceAndClosesTheCamera() {
        val policy = BackgroundPolicy()
        policy.recordingChanged(true)
        policy.hidden()
        // Notification Stop or a guard (D47) ends the take while off screen.
        assertEquals(step(ServiceChange.STOP, CameraChange.CLOSE), policy.recordingChanged(false))
        assertEquals(step(camera = CameraChange.REOPEN), policy.shown())
    }

    @Test
    fun settingOffKeepsTheOldBehaviour() {
        val policy = BackgroundPolicy(enabled = false)
        assertEquals(step(), policy.recordingChanged(true))
        assertFalse(policy.serviceRunning)
        assertEquals(step(camera = CameraChange.CLOSE), policy.hidden())
        assertEquals(step(camera = CameraChange.REOPEN), policy.shown())
    }

    @Test
    fun turningTheSettingOffDuringATakeStopsTheService() {
        val policy = BackgroundPolicy()
        policy.recordingChanged(true)
        assertEquals(step(ServiceChange.STOP), policy.setEnabled(false))
        assertEquals(step(camera = CameraChange.CLOSE), policy.hidden())
    }

    @Test
    fun theServiceIsNeverStartedOffScreen() {
        // Android 14+: a camera/microphone foreground service may only start while the app is visible.
        val policy = BackgroundPolicy(enabled = false)
        policy.hidden()
        assertEquals(step(), policy.setEnabled(true))
        assertEquals(step(), policy.recordingChanged(true))
        assertFalse(policy.serviceRunning)
        assertEquals(step(ServiceChange.START, CameraChange.REOPEN), policy.shown())
    }

    @Test
    fun theCameraRoleAloneKeepsTheCameraOffScreen() {
        val policy = BackgroundPolicy()
        assertEquals(step(ServiceChange.START), policy.roleChanged(true))
        assertEquals(step(camera = CameraChange.KEEP), policy.hidden())
        // A remote REC and stop while off screen: the role still needs the camera.
        assertEquals(step(), policy.recordingChanged(true))
        assertEquals(step(), policy.recordingChanged(false))
        assertTrue(policy.detached)
        // The role ends (notification Stop): service and camera go.
        assertEquals(step(ServiceChange.STOP, CameraChange.CLOSE), policy.roleChanged(false))
    }

    @Test
    fun aTakeOutlivesTheRoleOffScreen() {
        val policy = BackgroundPolicy()
        policy.roleChanged(true)
        policy.recordingChanged(true)
        policy.hidden()
        assertEquals(step(), policy.roleChanged(false))
        assertTrue(policy.serviceRunning)
        assertEquals(step(ServiceChange.STOP, CameraChange.CLOSE), policy.recordingChanged(false))
    }

    @Test
    fun aLostServiceClosesTheKeptCamera() {
        val policy = BackgroundPolicy()
        policy.recordingChanged(true)
        policy.hidden()
        assertEquals(step(camera = CameraChange.CLOSE), policy.serviceLost())
        assertFalse(policy.detached)
    }

    @Test
    fun aRefusedServiceMeansTheTakeIsSavedOnStop() {
        val policy = BackgroundPolicy()
        policy.recordingChanged(true)
        // Refused while visible: not retried in a loop.
        assertEquals(step(), policy.serviceLost())
        assertEquals(step(camera = CameraChange.CLOSE), policy.hidden())
        // The next take tries again.
        policy.shown()
        policy.recordingChanged(false)
        assertEquals(step(ServiceChange.START), policy.recordingChanged(true))
    }

    @Test
    fun leavingForAnotherScreenForgetsTheKeptCamera() {
        val policy = BackgroundPolicy()
        policy.recordingChanged(true)
        policy.hidden()
        policy.left()
        assertEquals(step(camera = CameraChange.REOPEN), policy.shown())
    }

    @Test
    fun hidingWithNothingRunningJustCloses() {
        val policy = BackgroundPolicy()
        assertEquals(step(camera = CameraChange.CLOSE), policy.hidden())
        assertFalse(policy.serviceRunning)
    }
}
