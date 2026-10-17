package com.qtekfun.ultimatecamera.core.recording

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.PhotoSizes
import com.qtekfun.ultimatecamera.core.camera.SessionRejectedException
import com.qtekfun.ultimatecamera.core.camera.StillCaptureStream
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.storage.OutputNaming
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What the photo mode shows. */
sealed interface PhotoState {
    data object Closed : PhotoState

    data class Ready(val sizes: PhotoSizes, val capturing: Boolean = false) : PhotoState

    data class Failed(val message: String) : PhotoState
}

/**
 * The basic photo mode (decision D41): the preview goes through the [CameraRenderer] in 8-bit SDR at a size with the
 * JPEG's aspect, and the shutter takes one full-size JPEG that is written through core-storage. Owns the camera while
 * open: close the video [CameraController] first. Call from one coroutine scope.
 */
class PhotoController(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("uc-photo").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val _state = MutableStateFlow<PhotoState>(PhotoState.Closed)
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    private var stream: StillCaptureStream? = null
    private var renderer: CameraRenderer? = null
    private var chars: CameraCharacteristics? = null
    private var preview: Surface? = null
    private var previewRotation = 0

    /** Opens [cameraId] for photos and starts the preview. Closes whatever was open. */
    @Suppress("TooGenericExceptionCaught") // Vendor HALs throw anything; the screen shows it instead of crashing.
    suspend fun open(cameraId: String) = withContext(Dispatchers.Default) {
        close()
        try {
            val c = manager.getCameraCharacteristics(cameraId).also { chars = it }
            val sizes = StillCaptureStream.sizes(c) ?: throw SessionRejectedException("no JPEG output on camera $cameraId")
            val r = CameraRenderer(
                EglColorDepth.RGBA8888,
                sizes.preview.width,
                sizes.preview.height,
                hlg = false,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            ).also { renderer = it }
            r.previewRotation = previewRotation
            preview?.let(r::setPreview)
            stream = StillCaptureStream.open(manager, cameraId, r.cameraSurface, sizes.jpeg, cameraHandler) { reason ->
                _state.value = PhotoState.Failed(reason)
            }
            _state.value = PhotoState.Ready(sizes)
        } catch (e: Exception) {
            release()
            _state.value = PhotoState.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** JPEG rotation (and preview turn) for a display rotated by [displayRotationDegrees]; 0 while closed. */
    fun uprightRotation(displayRotationDegrees: Int): Int = chars?.let { CameraController.uprightRotation(it, displayRotationDegrees) } ?: 0

    /** The display's rotation (0, 90, 180, 270); the preview is turned by it (see [CameraRenderer.previewRotation]). */
    fun setPreviewRotation(degrees: Int) {
        previewRotation = degrees
        renderer?.previewRotation = degrees
    }

    fun setPreview(surface: Surface?) {
        preview = surface
        renderer?.setPreview(surface)
    }

    /**
     * Takes one photo upright for [displayRotationDegrees] and saves it in [location] under the next free
     * `UC_…_CAM<id>_P<nnn>.jpg` name; returns that name. Throws [IOException] when it cannot be taken or saved.
     */
    @Suppress("TooGenericExceptionCaught") // Camera and HAL failures become IOException for the caller.
    suspend fun take(backend: StorageBackend, location: RecordingLocation, cameraId: String, displayRotationDegrees: Int): String =
        withContext(Dispatchers.IO) {
            val ready = _state.value as? PhotoState.Ready ?: throw IOException("camera not ready")
            val s = stream ?: throw IOException("camera not ready")
            _state.value = ready.copy(capturing = true)
            try {
                val name = OutputNaming.nextPhoto(LocalDateTime.now(), cameraId, backend.listFileNames(location))
                s.capture(uprightRotation(displayRotationDegrees)) { jpeg -> save(backend, location, name, jpeg) }
                name
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                (_state.value as? PhotoState.Ready)?.let { _state.value = it.copy(capturing = false) }
            }
        }

    /** Writes the JPEG bytes straight from the image buffer, fsyncs and closes; a failed write leaves no file. */
    private fun save(backend: StorageBackend, location: RecordingLocation, name: String, jpeg: ByteBuffer) {
        val bytes = ByteArray(jpeg.remaining()).also { jpeg.get(it) }
        val file = backend.createFile(location, name, MIME_TYPE)
        try {
            file.write(bytes, 0, bytes.size)
            file.sync()
            file.close()
        } catch (e: IOException) {
            runCatching { file.delete() }
            throw e
        }
    }

    /** Closes the camera (preview off). */
    fun close() {
        release()
        _state.value = PhotoState.Closed
    }

    private fun release() {
        stream?.close()
        stream = null
        renderer?.release()
        renderer = null
    }

    /** Frees the camera thread; the controller cannot be used afterwards. */
    fun shutdown() {
        release()
        cameraThread.quitSafely()
    }

    companion object {
        const val MIME_TYPE = "image/jpeg"
    }
}
