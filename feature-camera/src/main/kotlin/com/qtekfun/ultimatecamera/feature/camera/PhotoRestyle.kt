package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import com.qtekfun.ultimatecamera.core.recording.RestylePlan
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A photo saved by this session: where and under which name. */
data class SavedPhoto(val location: RecordingLocation, val name: String)

/**
 * What the restyle needs from storage and the image code; [P] is the preview image (a bitmap on the phone). Every call
 * may block: implementations run off the main thread themselves. Failures are [IOException].
 */
interface RestyleStore<P> {
    /** What [photo]'s XMP says (style, unstyled copy). */
    suspend fun inspect(photo: SavedPhoto): StyleXmp.Info

    /** True when [name] is in [photo]'s folder. */
    suspend fun exists(photo: SavedPhoto, name: String): Boolean

    /** A small upright preview of [source] (in [photo]'s folder) with [style], turned like [photo]. */
    suspend fun preview(photo: SavedPhoto, source: String, style: StyleParams): P

    /** Saves a new JPEG of [source] with [style], [photo]'s EXIF and a style XMP naming [base]; returns its name. */
    suspend fun save(photo: SavedPhoto, source: String, style: StyleParams, base: String?): String

    /** Frees what the previews keep (the decoded source). */
    fun release()
}

/**
 * The last-photo viewer's state (D79 follow-up): the [photo] shown, the [plan] (where a new style takes its pixels
 * from, or why it cannot), the [style] being tuned (starting from the [original] one), its [preview], and the outcome
 * of the last save ([saved] name, or [failed]).
 */
data class RestyleUi<P>(
    val photo: SavedPhoto? = null,
    val loading: Boolean = false,
    val plan: RestylePlan? = null,
    val original: StyleParams = STANDARD,
    val style: StyleParams = STANDARD,
    val preview: P? = null,
    val saving: Boolean = false,
    val saved: String? = null,
    val failed: Boolean = false
) {
    /** A new JPEG can be saved: the plan allows it, nothing is running and the style differs from the photo's. */
    val canSave: Boolean get() = plan?.possible == true && !loading && !saving && style != original

    companion object {
        val STANDARD = StyleParams.of(StylePreset.STANDARD)
    }
}

/**
 * Restyle of the last photo (D79 follow-up): open it, tune a style on a small preview, save a new styled JPEG without
 * re-capturing. Previews follow the tuner with a short debounce (a drag makes one preview, not one per frame) and the
 * newest wins; the save is the full-size strip pipeline of [com.qtekfun.ultimatecamera.core.recording.PhotoStyler].
 */
@Suppress("SwallowedException") // A read, preview or save failure is the viewer's "could not be restyled" note.
class PhotoRestyle<P>(private val scope: CoroutineScope, private val store: RestyleStore<P>) {
    private val _state = MutableStateFlow(RestyleUi<P>())
    val state: StateFlow<RestyleUi<P>> = _state.asStateFlow()
    private var previewJob: Job? = null
    private var openJob: Job? = null

    /** Opens the viewer on [photo]: reads its XMP, decides the plan and shows it as it is. */
    fun open(photo: SavedPhoto) {
        openJob?.cancel()
        previewJob?.cancel()
        _state.value = RestyleUi(photo = photo, loading = true)
        openJob = scope.launch {
            try {
                val info = store.inspect(photo)
                val base = RestylePlan.baseCandidate(photo.name, info)
                val plan = RestylePlan.of(info, store.exists(photo, base), base)
                val original = info.style ?: RestyleUi.STANDARD
                // A blocked plan still shows the photo itself, unchanged.
                val source = RestylePlan.source(plan, photo.name) ?: photo.name
                val shown = if (plan.possible) original else RestyleUi.STANDARD
                val preview = store.preview(photo, source, shown)
                _state.update { it.copy(loading = false, plan = plan, original = original, style = original, preview = preview) }
            } catch (e: IOException) {
                _state.update { it.copy(loading = false, failed = true, plan = it.plan ?: RestylePlan.Unknown) }
            }
        }
    }

    /** A new style from the pad, sliders or presets; the preview follows when nothing changes for a moment. */
    fun setStyle(style: StyleParams) {
        val now = _state.value
        val photo = now.photo ?: return
        val source = now.plan?.let { RestylePlan.source(it, photo.name) } ?: return
        if (now.loading || now.style == style) return
        _state.update { it.copy(style = style, saved = null, failed = false) }
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            try {
                val preview = store.preview(photo, source, style)
                _state.update { if (it.style == style) it.copy(preview = preview) else it }
            } catch (e: IOException) {
                _state.update { it.copy(failed = true) }
            }
        }
    }

    /** Saves the tuned style as a new JPEG next to the photo; the photo and its copy stay as they are. */
    fun save() {
        val now = _state.value
        val photo = now.photo ?: return
        val plan = now.plan ?: return
        val source = RestylePlan.source(plan, photo.name) ?: return
        if (!now.canSave) return
        val style = now.style
        _state.update { it.copy(saving = true, saved = null, failed = false) }
        scope.launch {
            try {
                val name = store.save(photo, source, style, base = source)
                _state.update { it.copy(saving = false, saved = name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(saving = false, failed = true) }
            }
        }
    }

    /** Closes the viewer and frees the preview. */
    fun close() {
        openJob?.cancel()
        previewJob?.cancel()
        _state.value = RestyleUi()
        store.release()
    }

    companion object {
        const val PREVIEW_DEBOUNCE_MS = 150L
    }
}
