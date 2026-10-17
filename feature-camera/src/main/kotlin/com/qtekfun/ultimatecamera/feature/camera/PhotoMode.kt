package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import android.view.Surface
import com.qtekfun.ultimatecamera.core.recording.PhotoController
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import com.qtekfun.ultimatecamera.core.storage.RecordingLocationRepository
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the shutter does: record video (the default) or take photos (decision D41). */
enum class CaptureMode { VIDEO, PHOTO }

/** The photo mode as the camera screen shows it; [shots] grows on every shutter press, for the flash feedback. */
data class PhotoUiState(val mode: CaptureMode = CaptureMode.VIDEO, val shots: Int = 0)

/**
 * The camera screen's photo mode (D41), kept apart from [CameraViewModel] so the video path stays as it is: the view
 * model only forwards the lifecycle, preview surface and rotation, and switches the camera between the two.
 */
class PhotoMode(context: Context, private val backend: StorageBackend, private val locations: RecordingLocationRepository) {
    private val controller = PhotoController(context)
    private var displayRotation = 0

    private val _state = MutableStateFlow(PhotoUiState())
    val state: StateFlow<PhotoUiState> = _state.asStateFlow()

    /** The photo camera: closed in video mode. */
    val camera: StateFlow<PhotoState> = controller.state

    val active: Boolean get() = _state.value.mode == CaptureMode.PHOTO

    /** Sets the mode; false when it already was [mode]. The caller opens and closes the cameras. */
    fun select(mode: CaptureMode): Boolean {
        if (_state.value.mode == mode) return false
        _state.update { it.copy(mode = mode) }
        return true
    }

    suspend fun open(cameraId: String) {
        controller.open(cameraId)
        setDisplayRotation(displayRotation)
    }

    fun close() {
        controller.close()
    }

    fun setPreview(surface: Surface?) = controller.setPreview(surface)

    fun setDisplayRotation(degrees: Int) {
        displayRotation = degrees
        controller.setPreviewRotation(controller.uprightRotation(degrees))
    }

    /** Takes and saves one photo; returns the note for the snackbar (null when the camera is not ready). */
    suspend fun take(cameraId: String): CameraMessage? {
        if (controller.state.value !is PhotoState.Ready) return null
        val target = locations.check().effective ?: return CameraMessage.NoStorage
        _state.update { it.copy(shots = it.shots + 1) }
        return try {
            CameraMessage.Saved(controller.take(backend, target, cameraId, displayRotation))
        } catch (e: IOException) {
            CameraMessage.Problem(e.message ?: "photo")
        }
    }

    fun shutdown() = controller.shutdown()
}
