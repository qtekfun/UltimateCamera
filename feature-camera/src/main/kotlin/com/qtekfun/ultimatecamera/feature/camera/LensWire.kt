package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import com.qtekfun.ultimatecamera.core.camera.CameraControls
import com.qtekfun.ultimatecamera.core.camera.ControlRanges
import com.qtekfun.ultimatecamera.core.camera.LensCatalog
import com.qtekfun.ultimatecamera.core.camera.LensKind
import com.qtekfun.ultimatecamera.core.camera.LensReader
import com.qtekfun.ultimatecamera.core.camera.LensStop
import com.qtekfun.ultimatecamera.core.camera.VideoStabilization
import com.qtekfun.ultimatecamera.core.recording.CameraController
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the lens selector shows (REQ-CAM-001, REQ-CAM-003). */
data class LensUiState(
    /** This phone's lenses, null until read. */
    val catalog: LensCatalog? = null,
    /** The camera open now. */
    val cameraId: String? = null,
    /** Electronic stabilisation asked for (applied at the next opening, never mid-take). */
    val stabilization: VideoStabilization = VideoStabilization.OFF
) {
    val front: Boolean get() = cameraId != null && catalog?.kindOf(cameraId) == LensKind.FRONT
}

/**
 * The camera screen's lenses and stabilisation (D62), kept out of [CameraViewModel] so it only needs small hooks. A stop
 * on the open camera only changes `CONTROL_ZOOM_RATIO` through the controls path (smooth, also during a take); a stop on
 * another camera id, the front toggle and a stabilisation change go through [reopen] (refused while recording).
 */
class LensWire internal constructor(
    private val context: Context,
    private val controller: CameraController,
    private val scope: CoroutineScope,
    /** Reopens on camera id (null = the same one) at the zoom ratio, with the stabilisation set on the controller. */
    private val reopen: suspend (cameraId: String?, zoomRatio: Float) -> Unit
) {
    private val _state = MutableStateFlow(LensUiState())
    val state: StateFlow<LensUiState> = _state.asStateFlow()

    val controls: StateFlow<CameraControls> get() = controller.controls
    val ranges: StateFlow<ControlRanges?> get() = controller.ranges

    /** The back stop to return to from the front camera. */
    private var lastBack: LensStop? = null

    /** Reads the lenses once (characteristics only, no camera opened). */
    suspend fun load() {
        if (_state.value.catalog != null) return
        val catalog = withContext(Dispatchers.Default) {
            runCatching { LensReader.catalog(context.getSystemService(CameraManager::class.java)) }.getOrNull()
        }
        _state.update { it.copy(catalog = catalog) }
    }

    /** Camera [id] is open now. */
    fun cameraOpened(id: String) = _state.update { it.copy(cameraId = id) }

    /** Goes to [stop]: a zoom ratio on the open camera, or (not while recording) another camera. */
    fun select(stop: LensStop) {
        if (stop.cameraId == _state.value.cameraId) {
            setZoom(stop.zoomRatio)
        } else {
            scope.launch { reopen(stop.cameraId, stop.zoomRatio) }
        }
    }

    /** Front camera on or off (back to the last back stop); not while recording (the caller disables it). */
    fun toggleFront() {
        val s = _state.value
        val catalog = s.catalog ?: return
        val id = s.cameraId ?: return
        if (s.front) {
            val back = lastBack ?: catalog.back.firstOrNull { it.kind == LensKind.MAIN } ?: return
            scope.launch { reopen(back.cameraId, back.zoomRatio) }
        } else {
            val front = catalog.front ?: return
            lastBack = LensStop(LensKind.MAIN, id, controls.value.zoomRatio, catalog.factorOf(id))
            scope.launch { reopen(front.cameraId, 1f) }
        }
    }

    /** Multiplies the zoom by [scale] (pinch), clamped to the open camera's range; tiny steps are not sent. */
    fun zoomBy(scale: Float) {
        val r = ranges.value ?: return
        if (!r.zoomable) return
        val now = controls.value.zoomRatio
        val next = (now * scale).coerceIn(r.zoomRange)
        if (abs(next - now) > now * MIN_STEP) setZoom(next)
    }

    private fun setZoom(ratio: Float) = controller.setControls(controls.value.copy(zoomRatio = ratio))

    /** Electronic stabilisation: reopens the camera (a session parameter), so the caller disables it while recording. */
    fun setStabilization(mode: VideoStabilization) {
        if (mode == _state.value.stabilization) return
        controller.stabilization = mode
        _state.update { it.copy(stabilization = mode) }
        scope.launch { reopen(null, controls.value.zoomRatio) }
    }

    /** Optical stabilisation, a per-frame key: applies at once. */
    fun setOis(on: Boolean) = controller.setControls(controls.value.copy(ois = on))

    private companion object {
        /** Half a percent: pinch events are frequent, the repeating request is not rebuilt for less. */
        const val MIN_STEP = 0.005f
    }
}
