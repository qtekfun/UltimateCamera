package com.qtekfun.ultimatecamera.core.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** A camera that failed to open or was lost (disconnected, evicted by another app, an error). */
class CameraLostException(message: String) : RuntimeException(message)

/**
 * Opens camera [id]. [onLost] is called (on [handler]) if the camera is disconnected or fails after opening; the caller
 * must stop using the device then. Requires the CAMERA permission, which the caller checks.
 */
@SuppressLint("MissingPermission")
suspend fun CameraManager.openCameraDevice(id: String, handler: Handler, onLost: (String) -> Unit = {}): CameraDevice = suspendCancellableCoroutine { cont ->
    openCamera(
        id,
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) cont.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(CameraLostException("camera $id disconnected")) else onLost("camera $id disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                val message = "camera $id error $error"
                if (cont.isActive) cont.resumeWithException(CameraLostException(message)) else onLost(message)
            }
        },
        handler
    )
}

/** Creates a capture session with [outputs]; callbacks run on [executor]. */
suspend fun CameraDevice.createSession(outputs: List<OutputConfiguration>, executor: Executor): CameraCaptureSession = suspendCancellableCoroutine { cont ->
    val config = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        outputs,
        executor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resume(session) else session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_ERROR, "session configuration failed"))
            }
        }
    )
    createCaptureSession(config)
}
