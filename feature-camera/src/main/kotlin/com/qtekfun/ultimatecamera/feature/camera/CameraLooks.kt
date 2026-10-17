package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.LutCompose
import com.qtekfun.ultimatecamera.core.gpu.UcLogLuts
import com.qtekfun.ultimatecamera.core.gpu.style.StyleEngine
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.luts.LutEntry
import com.qtekfun.ultimatecamera.core.luts.LutLibraries
import com.qtekfun.ultimatecamera.core.luts.LutLibrary
import com.qtekfun.ultimatecamera.core.luts.LutLibrarySnapshot
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.recording.ColorSetup
import com.qtekfun.ultimatecamera.core.settings.LookSlot
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import com.qtekfun.ultimatecamera.core.settings.StyleDefaults
import com.qtekfun.ultimatecamera.core.settings.styles
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the LUT picker shows. [missing] is set when a chosen library LUT could not be loaded (deleted, unreadable);
 * [defaultStyle] is Settings › Styles' video style, which applies when the recording look is "none" (D74).
 */
data class LooksUiState(
    val recording: RecordingLook = RecordingLook.None,
    val defaultStyle: StyleParams? = null,
    val monitor: MonitorLook = MonitorLook.Auto,
    val tenBit: Boolean = true,
    val library: List<LutEntry> = emptyList(),
    val missing: Boolean = false
)

/**
 * Keeps the camera's colour stages in line with the persisted looks (REQ-COL-002/004): parses the choices, loads the
 * LUTs (library files are parsed once per choice, the UC-Log viewing LUT is generated once), chains the monitor LUTs on
 * the CPU and hands the result to [CameraController.setColor]. All loading is off the main thread.
 */
class CameraLooks(context: Context, private val settings: SettingsRepository, private val controller: CameraController, private val scope: CoroutineScope) {
    private val library: LutLibrary = LutLibraries.library(context)
    private val swatchCache = HashMap<String, File>()
    private val loaded = HashMap<LutRef, CubeLut>()
    private val viewingLut by lazy { UcLogLuts.toRec709() }

    private val _state = MutableStateFlow(LooksUiState())
    val state: StateFlow<LooksUiState> = _state.asStateFlow()

    init {
        scope.launch {
            library.snapshots.catch { emit(LutLibrarySnapshot(emptyList(), emptyList())) }
                .collect { snapshot ->
                    _state.update { it.copy(library = snapshot.luts.sortedWith(compareBy({ e -> e.folder }, { e -> e.title.lowercase() }))) }
                }
        }
        scope.launch {
            val tenBit = controller.state.mapNotNull {
                when (it) {
                    is CameraState.Previewing -> it.profile.tenBit
                    is CameraState.Recording -> it.profile.tenBit
                    else -> null
                }
            }.distinctUntilChanged()
            val looks = settings.settings.map { RecordingLook.parse(it.recordingLook) to MonitorLook.parse(it.monitorLook) }.distinctUntilChanged()
            val defaultStyle = (settings.styles?.styleDefaults ?: flowOf(StyleDefaults()))
                .catch { emit(StyleDefaults()) }
                .map { StyleParams.parse(it.video) }
                .distinctUntilChanged()
            combine(looks, tenBit, defaultStyle) { (recording, monitor), bits, style -> Looks(recording, monitor, bits, style) }.collectLatest { l ->
                _state.update { it.copy(recording = l.recording, monitor = l.monitor, tenBit = l.tenBit, defaultStyle = l.style) }
                apply(LookPlan.of(l.recording, l.monitor, l.tenBit, l.style))
            }
        }
    }

    private data class Looks(val recording: RecordingLook, val monitor: MonitorLook, val tenBit: Boolean, val style: StyleParams?)

    fun setRecording(look: RecordingLook) {
        scope.launch { settings.setLook(LookSlot.RECORDING, look.token) }
    }

    fun setMonitor(look: MonitorLook) {
        scope.launch { settings.setLook(LookSlot.MONITOR, look.token) }
    }

    /** The cached preview of a library LUT (rendered on first use), or null when it cannot be made. */
    @Suppress("SwallowedException") // No swatch is a blank square, not an error.
    suspend fun swatch(id: String): File? = swatchCache[id] ?: try {
        library.swatch(id).also { swatchCache[id] = it }
    } catch (e: IOException) {
        null
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun apply(plan: LookPlan) = withContext(Dispatchers.Default) {
        var missing = false
        fun get(ref: LutRef): CubeLut? = loaded[ref].also { if (it == null) missing = true }
        // Parse what the plan needs (and only that: a 65-point LUT is several MB) before handing it to the GPU.
        val needed = (listOfNotNull(plan.record) + plan.monitor).toSet()
        loaded.keys.retainAll(needed)
        for (ref in needed) {
            if (ref in loaded) continue
            val lut = when (ref) {
                LutRef.UcLogToRec709 -> viewingLut
                is LutRef.Style -> StyleEngine.lutFor(ref.params, ref.input)
                is LutRef.Library -> try {
                    library.load(ref.id)
                } catch (e: IOException) {
                    null
                }
            }
            if (lut != null) loaded[ref] = lut
        }
        val record = plan.record?.let(::get)
        val monitor = plan.monitor.mapNotNull(::get).reduceOrNull { first, second -> LutCompose.compose(first, second) }
        _state.update { it.copy(missing = missing) }
        try {
            controller.setColor(ColorSetup(ucLog = plan.ucLog, recordLut = record, monitorLut = monitor))
        } catch (e: RuntimeException) {
            // The renderer was closing (camera released meanwhile): the next open applies the stored setup anyway.
        }
    }
}
