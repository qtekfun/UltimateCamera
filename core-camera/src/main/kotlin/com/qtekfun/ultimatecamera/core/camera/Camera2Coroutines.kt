package com.qtekfun.ultimatecamera.core.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** A camera that failed to open or was lost (disconnected, evicted by another app, an error). */
class CameraLostException(message: String) : RuntimeException(message)

/** The HAL refused the session's outputs and parameters (for example 10-bit HLG above 30 fps on the Pixel 8). */
class SessionRejectedException(message: String) : RuntimeException(message)

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

/**
 * Creates a capture session with [outputs]; callbacks run on [executor]. [sessionParameters] (usually the repeating
 * request) lets the HAL pick the sensor mode at configure time: without it some HALs configure a 30 fps mode and then
 * fail a 60 fps request.
 */
suspend fun CameraDevice.createSession(
    outputs: List<OutputConfiguration>,
    executor: Executor,
    sessionParameters: CaptureRequest? = null
): CameraCaptureSession = suspendCancellableCoroutine { cont ->
    val config = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        outputs,
        executor,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resume(session) else session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(SessionRejectedException("session configuration failed"))
            }
        }
    )
    sessionParameters?.let(config::setSessionParameters)
    createCaptureSession(config)
}
