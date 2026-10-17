package com.qtekfun.ultimatecamera.feature.camera.background

/** What to do with the foreground service after an event. */
enum class ServiceChange { NONE, START, STOP }

/** What to do with the camera after an event. */
enum class CameraChange {
    NONE,

    /** The screen went away but the take or the role goes on: keep the camera open, the preview just detaches. */
    KEEP,

    /** Finish the take (if any) and close the camera (D40 behaviour). */
    CLOSE,

    /** Back on screen and the camera was kept: reattach the preview, do not reopen. */
    RESUME,

    /** Back on screen after a close: open the camera as usual. */
    REOPEN
}

data class BackgroundStep(val service: ServiceChange = ServiceChange.NONE, val camera: CameraChange = CameraChange.NONE)

/**
 * When a take or the multicamera camera role keeps running off screen (D66). Pure; the camera screen feeds it events.
 *
 * The service is wanted while the setting is on and a take or the role runs. Android 14+ only lets a camera or
 * microphone foreground service start while the app is visible, so it is started at REC / role start (never from the
 * background) and the camera is kept off screen only when the service is running. Off screen, once nothing needs the
 * camera any more (take stopped from the notification or a guard, role ended), the camera is closed.
 */
class BackgroundPolicy(enabled: Boolean = true) {
    var enabled = enabled
        private set
    var visible = true
        private set
    var recording = false
        private set
    var roleActive = false
        private set
    var serviceRunning = false
        private set

    /** The camera was kept open when the screen went away. */
    var detached = false
        private set

    /** The system refused the service for the current take/role: not retried until nothing needs it any more. */
    private var refused = false

    private val wanted get() = enabled && (recording || roleActive)

    fun setEnabled(on: Boolean): BackgroundStep {
        enabled = on
        return settle()
    }

    fun recordingChanged(on: Boolean): BackgroundStep {
        recording = on
        return settle()
    }

    fun roleChanged(on: Boolean): BackgroundStep {
        roleActive = on
        return settle()
    }

    /** The system refused or killed the service: the camera can no longer be kept off screen. */
    fun serviceLost(): BackgroundStep {
        serviceRunning = false
        refused = true
        return settle()
    }

    /** The screen stopped (Home, lock, another app). */
    fun hidden(): BackgroundStep {
        visible = false
        val keep = wanted && serviceRunning
        detached = keep
        val service = if (!keep && serviceRunning) stopService() else ServiceChange.NONE
        return BackgroundStep(service, if (keep) CameraChange.KEEP else CameraChange.CLOSE)
    }

    /** The screen started again. */
    fun shown(): BackgroundStep {
        visible = true
        val camera = if (detached) CameraChange.RESUME else CameraChange.REOPEN
        detached = false
        return settle().copy(camera = camera)
    }

    /** The camera screen was left for another screen, or closed everything itself: nothing is kept any more. */
    fun left() {
        detached = false
    }

    private fun settle(): BackgroundStep {
        if (!wanted) refused = false
        val service = when {
            wanted && !serviceRunning && visible && !refused -> {
                serviceRunning = true
                ServiceChange.START
            }
            !wanted && serviceRunning -> stopService()
            else -> ServiceChange.NONE
        }
        // Off screen with nothing left to keep running: close the camera (back to D40).
        val camera = if (detached && (!wanted || !serviceRunning)) {
            detached = false
            CameraChange.CLOSE
        } else {
            CameraChange.NONE
        }
        return BackgroundStep(service, camera)
    }

    private fun stopService(): ServiceChange {
        serviceRunning = false
        return ServiceChange.STOP
    }
}
