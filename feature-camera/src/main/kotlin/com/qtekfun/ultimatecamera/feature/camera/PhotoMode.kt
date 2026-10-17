package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.HdrChoice
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.recording.PhotoController
import com.qtekfun.ultimatecamera.core.recording.PhotoState
import com.qtekfun.ultimatecamera.core.settings.PhotoHdrDefault
import com.qtekfun.ultimatecamera.core.settings.PhotoHdrRepository
import com.qtekfun.ultimatecamera.core.settings.PortraitRepository
import com.qtekfun.ultimatecamera.core.settings.StyleRepository
import com.qtekfun.ultimatecamera.core.storage.RecordingLocationRepository
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** What the shutter does: record video (the default) or take photos (decision D41). */
enum class CaptureMode { VIDEO, PHOTO }

/**
 * The photo mode as the camera screen shows it; [shots] grows on every shutter press, for the flash feedback; [hdr] is
 * the HDR chip's choice (D64), starting from Settings › Photo; [style] is the style chip's (D79), starting from
 * Settings › Styles.
 */
data class PhotoUiState(
    val mode: CaptureMode = CaptureMode.VIDEO,
    val shots: Int = 0,
    val hdr: HdrChoice = HdrChoice.Auto,
    val portrait: PortraitUi = PortraitUi(),
    val style: StyleParams = StyleParams.of(StylePreset.STANDARD)
)

/**
 * The camera screen's photo mode (D41), kept apart from [CameraViewModel] so the video path stays as it is: the view
 * model only forwards the lifecycle, preview surface and rotation, and switches the camera between the two.
 */
class PhotoMode(
    context: Context,
    private val backend: StorageBackend,
    private val locations: RecordingLocationRepository,
    private val hdrPrefs: PhotoHdrRepository? = null,
    private val portraitPrefs: PortraitRepository? = null,
    private val experimental: Flow<Boolean>? = null,
    private val stylePrefs: StyleRepository? = null
) {
    private val controller = PhotoController(context)
    private var displayRotation = 0
    private var hdrDefaultRead = false

    private val _state = MutableStateFlow(PhotoUiState())
    val state: StateFlow<PhotoUiState> = _state.asStateFlow()

    /** The photo camera: closed in video mode. */
    val camera: StateFlow<PhotoState> = controller.state

    val active: Boolean get() = _state.value.mode == CaptureMode.PHOTO

    private val _lastPhoto = MutableStateFlow<SavedPhoto?>(null)

    /** The last photo this session saved, for the last-photo viewer and its restyle (null until the first shot). */
    val lastPhoto: StateFlow<SavedPhoto?> = _lastPhoto.asStateFlow()

    /** Sets the mode; false when it already was [mode]. The caller opens and closes the cameras. */
    fun select(mode: CaptureMode): Boolean {
        if (_state.value.mode == mode) return false
        _state.update { it.copy(mode = mode) }
        return true
    }

    suspend fun open(cameraId: String) {
        if (!hdrDefaultRead) {
            hdrDefaultRead = true
            val default = hdrPrefs?.photoHdrDefault?.first() ?: PhotoHdrDefault.AUTO
            _state.update { it.copy(hdr = if (default == PhotoHdrDefault.OFF) HdrChoice.Off else HdrChoice.Auto) }
            StyleParams.parse(stylePrefs?.styleDefaults?.first()?.photo)?.let { style -> _state.update { it.copy(style = style) } }
        }
        controller.keepBase = stylePrefs?.styleDefaults?.first()?.keepBase ?: false
        controller.style = _state.value.style
        controller.hdrChoice = _state.value.hdr
        val portrait = _state.value.portrait
        controller.portraitOptions = portrait.options(portraitPrefs?.portraitEnabled, experimental)
        controller.portraitLook = portrait.look
        controller.open(cameraId)
        setDisplayRotation(displayRotation)
    }

    fun close() {
        controller.close()
    }

    fun setPreview(surface: Surface?) = controller.setPreview(surface)

    fun setDisplayRotation(degrees: Int) {
        displayRotation = degrees
        controller.setPreviewRotation(degrees)
    }

    /** Takes and saves one photo; returns the note for the snackbar (null when the camera is not ready). */
    suspend fun take(cameraId: String): CameraMessage? {
        if (controller.state.value !is PhotoState.Ready) return null
        val target = locations.check().effective ?: return CameraMessage.NoStorage
        _state.update { it.copy(shots = it.shots + 1) }
        return try {
            val name = controller.take(backend, target, cameraId, displayRotation)
            _lastPhoto.value = SavedPhoto(target, name)
            CameraMessage.Saved(name)
        } catch (e: IOException) {
            CameraMessage.Problem(e.message ?: "photo")
        }
    }

    /** Sets the HDR choice for this session and reopens the photo camera with it (only when in photo mode). */
    suspend fun setHdr(choice: HdrChoice, cameraId: String) {
        if (_state.value.hdr == choice) return
        _state.update { it.copy(hdr = choice) }
        if (active) open(cameraId)
    }

    /** Sets the style of the next photos for this session (D79); no reopen needed, it is applied after the capture. */
    fun setStyle(style: StyleParams) {
        _state.update { it.copy(style = style) }
        controller.style = style
    }

    /** Sets the Portrait chip (D73); turning it on or off reopens the photo camera, the aperture and PNG apply at once. */
    suspend fun setPortrait(portrait: PortraitUi, cameraId: String) {
        val before = _state.value.portrait
        if (before == portrait) return
        _state.update { it.copy(portrait = portrait) }
        controller.portraitLook = portrait.look
        if (active && before.reopens(portrait)) open(cameraId)
    }

    fun shutdown() = controller.shutdown()
}
