package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.camera.ImagePoint
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthPng16
import com.qtekfun.ultimatecamera.core.gpu.portrait.PlaneTurn
import com.qtekfun.ultimatecamera.core.recording.PortraitLook
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The edit files of a portrait shot (D88): the unblurred photo, the 16-bit depth and what that depth says. */
data class PortraitSource(val sharp: String, val depth: String, val meta: DepthPng16.Meta)

/**
 * What the portrait edit needs from storage and the image code; [P] is the preview image (a bitmap on the phone).
 * Every call may block: implementations run off the main thread themselves. Failures are [IOException].
 */
interface PortraitEditStore<P> {
    /** The edit files of [photo]'s shot, or null when it kept none (not a portrait, or kept without them). */
    suspend fun find(photo: SavedPhoto): PortraitSource?

    /** An upright preview of [source] rendered with [look] and [photo]'s style. */
    suspend fun preview(photo: SavedPhoto, source: PortraitSource, look: PortraitLook): P

    /** Saves a new JPEG of [source] with [look], [photo]'s EXIF and style; returns its name. */
    suspend fun save(photo: SavedPhoto, source: PortraitSource, look: PortraitLook): String

    /** Frees what the previews keep (the decoded photo and depth). */
    fun release()
}

/**
 * The portrait edit's state (D88): the [photo], its edit files ([source], null = not editable), the aperture slider
 * position ([aperture], 0 = no blur) and the focus point in the sensor frame ([focus]), starting from the capture's
 * ([originalAperture], [originalFocus]); the [preview] and the outcome of the last save.
 */
data class PortraitEditUi<P>(
    val photo: SavedPhoto? = null,
    val loading: Boolean = false,
    val source: PortraitSource? = null,
    val aperture: Int = 0,
    val focus: ImagePoint = CENTRE,
    val originalAperture: Int = 0,
    val originalFocus: ImagePoint = CENTRE,
    val preview: P? = null,
    val rendering: Boolean = false,
    val saving: Boolean = false,
    val saved: String? = null,
    val failed: Boolean = false
) {
    /** The shot kept its edit files. */
    val available: Boolean get() = source != null

    /** Clockwise turn from the sensor frame to the upright photo. */
    val rotation: Int get() = source?.meta?.rotation ?: 0

    /** The render of the current values (no files besides the photo). */
    val look: PortraitLook get() = PortraitLook(Bokeh.fNumber(aperture), focus.x, focus.y, saveDepth = false, keepEdit = false)

    /** A new JPEG can be saved: editable, nothing running, and the aperture or focus differ from the capture's. */
    val canSave: Boolean get() = available && !loading && !saving && (aperture != originalAperture || focus != originalFocus)

    /** The focus point on the upright preview (normalised), for the ring. */
    val focusUpright: Pair<Float, Float> get() = PlaneTurn.toUpright(focus.x, focus.y, rotation)

    companion object {
        val CENTRE = ImagePoint(0.5f, 0.5f)

        /** The slider position of the aperture [fNumber] (0 = no blur, else the nearest of [Bokeh.STOPS]). */
        fun position(fNumber: Float): Int = if (fNumber <= 0f) 0 else Bokeh.STOPS.indices.minByOrNull { abs(Bokeh.STOPS[it] - fNumber) }?.plus(1) ?: 0

        /**
         * Where a tap at ([x], [y]) in a [boxWidth] x [boxHeight] box lands on an [imageWidth] x [imageHeight] image
         * shown fitted and centred in it (`ContentScale.Fit`), normalised; null outside the image (the bars).
         */
        @Suppress("LongParameterList")
        fun tapOnFitted(x: Float, y: Float, boxWidth: Float, boxHeight: Float, imageWidth: Int, imageHeight: Int): Pair<Float, Float>? {
            val rect = fitted(boxWidth, boxHeight, imageWidth, imageHeight) ?: return null
            val u = (x - rect.left) / rect.width
            val v = (y - rect.top) / rect.height
            return if (u in 0f..1f && v in 0f..1f) u to v else null
        }

        /** The box position of the normalised image point ([u], [v]) of a fitted image (the inverse of [tapOnFitted]). */
        @Suppress("LongParameterList")
        fun onFitted(u: Float, v: Float, boxWidth: Float, boxHeight: Float, imageWidth: Int, imageHeight: Int): Pair<Float, Float>? {
            val rect = fitted(boxWidth, boxHeight, imageWidth, imageHeight) ?: return null
            return rect.left + u * rect.width to rect.top + v * rect.height
        }

        private class Rect(val left: Float, val top: Float, val width: Float, val height: Float)

        private fun fitted(boxWidth: Float, boxHeight: Float, imageWidth: Int, imageHeight: Int): Rect? {
            if (minOf(boxWidth, boxHeight) <= 0f || minOf(imageWidth, imageHeight) <= 0) return null
            val scale = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
            val w = imageWidth * scale
            val h = imageHeight * scale
            return Rect((boxWidth - w) / 2f, (boxHeight - h) / 2f, w, h)
        }
    }
}

/**
 * Edit after capture of a portrait photo (REQ-PHO-003, D88): change the aperture and the focus point (a tap on the
 * photo) on a preview and save a new JPEG, never overwriting. Previews follow with a short debounce and the newest
 * wins, as in [PhotoRestyle]; the first preview is made only when the portrait tab is shown ([show]).
 */
@Suppress("SwallowedException") // A read, preview or save failure is the viewer's "could not be edited" note.
class PortraitEdit<P>(private val scope: CoroutineScope, private val store: PortraitEditStore<P>) {
    private val _state = MutableStateFlow(PortraitEditUi<P>())
    val state: StateFlow<PortraitEditUi<P>> = _state.asStateFlow()
    private var previewJob: Job? = null
    private var openJob: Job? = null

    /** Opens [photo]: finds its edit files and starts from the capture's aperture and focus. */
    fun open(photo: SavedPhoto) {
        openJob?.cancel()
        previewJob?.cancel()
        _state.value = PortraitEditUi(photo = photo, loading = true)
        openJob = scope.launch {
            try {
                val source = store.find(photo)
                if (source == null) {
                    _state.update { it.copy(loading = false) }
                } else {
                    val aperture = PortraitEditUi.position(source.meta.fNumber)
                    val focus = ImagePoint(source.meta.focusX.coerceIn(0f, 1f), source.meta.focusY.coerceIn(0f, 1f))
                    _state.update {
                        it.copy(loading = false, source = source, aperture = aperture, focus = focus, originalAperture = aperture, originalFocus = focus)
                    }
                }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, source = null) }
            }
        }
    }

    /** The portrait tab is shown: makes the first preview if there is none yet. */
    fun show() {
        val now = _state.value
        if (now.available && now.preview == null && !now.rendering) render(debounce = false)
    }

    /** A new slider position (0 = no blur, then [Bokeh.STOPS]). */
    fun setAperture(position: Int) {
        val now = _state.value
        val p = position.coerceIn(0, Bokeh.STOPS.size)
        if (!now.available || now.loading || now.aperture == p) return
        _state.update { it.copy(aperture = p, saved = null, failed = false) }
        render(debounce = true)
    }

    /** A tap at the normalised point ([u], [v]) of the upright preview: the focus there (sensor frame). */
    fun tapUpright(u: Float, v: Float) {
        val now = _state.value
        if (!now.available || now.loading) return
        val (x, y) = PlaneTurn.toSensor(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), now.rotation)
        val point = ImagePoint(x, y)
        if (point == now.focus) return
        _state.update { it.copy(focus = point, saved = null, failed = false) }
        render(debounce = true)
    }

    private fun render(debounce: Boolean) {
        val now = _state.value
        val photo = now.photo ?: return
        val source = now.source ?: return
        val look = now.look
        previewJob?.cancel()
        _state.update { it.copy(rendering = true) }
        previewJob = scope.launch {
            if (debounce) delay(PREVIEW_DEBOUNCE_MS)
            try {
                val preview = store.preview(photo, source, look)
                _state.update { if (it.look == look) it.copy(preview = preview, rendering = false) else it }
            } catch (e: IOException) {
                _state.update { it.copy(failed = true, rendering = false) }
            }
        }
    }

    /** Saves the current aperture and focus as a new JPEG next to the photo; the photo and its edit files stay. */
    fun save() {
        val now = _state.value
        val photo = now.photo ?: return
        val source = now.source ?: return
        if (!now.canSave) return
        val look = now.look
        _state.update { it.copy(saving = true, saved = null, failed = false) }
        scope.launch {
            try {
                val name = store.save(photo, source, look)
                _state.update { it.copy(saving = false, saved = name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(saving = false, failed = true) }
            }
        }
    }

    /** Closes the edit and frees the preview. */
    fun close() {
        openJob?.cancel()
        previewJob?.cancel()
        _state.value = PortraitEditUi()
        store.release()
    }

    companion object {
        const val PREVIEW_DEBOUNCE_MS = 200L
    }
}
