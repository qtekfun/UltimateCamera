package com.qtekfun.ultimatecamera.feature.camera

import android.app.Application
import com.qtekfun.ultimatecamera.core.capabilities.AndroidCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.CapabilitySelector
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.SensorHdrExtension
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.recording.HeldMode
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.recording.VendorSession
import com.qtekfun.ultimatecamera.core.settings.HdrPrefs
import com.qtekfun.ultimatecamera.core.settings.HdrRepository
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the camera screen shows of HDR (D63). */
data class HdrUiState(
    /** The preview is HDR now: the window asks for the HDR colour mode. */
    val previewHdr: Boolean = false,
    /** The "HDR (sensor)" quick toggle is offered: Experimental on and the probe held the mode for this profile. */
    val sensorAvailable: Boolean = false,
    /** The toggle is on (the stream runs with the vendor HDR mode when [sensorActive]). */
    val sensorOn: Boolean = false,
    val sensorActive: Boolean = false
)

/**
 * HDR on the camera screen (D63), kept out of [CameraViewModel] so it needs only small hooks: the HDR viewfinder (the
 * setting and the display's HDR capability go to [CameraController.hdrViewfinderAllowed]; the screen follows
 * [HdrUiState.previewHdr] with the window's colour mode) and the experimental sensor HDR toggle, offered only where the
 * auto-configuration measured the vendor variant to hold ([CapabilitySelector.heldVariants]).
 */
class HdrWire internal constructor(
    private val application: Application,
    settings: SettingsRepository,
    private val controller: CameraController,
    private val scope: CoroutineScope,
    /** Reopens the camera in the current profile (a vendor session is set at configure time); no-op while recording. */
    private val reopen: () -> Unit
) {
    private val repo = settings as? HdrRepository
    private var prefs = HdrPrefs()
    private val displayHdr = MutableStateFlow(false)
    private var capabilities: DeviceCapabilities? = null
    private var loaded = false

    private val _state = MutableStateFlow(HdrUiState())
    val state: StateFlow<HdrUiState> = _state.asStateFlow()

    init {
        repo?.let { r ->
            scope.launch {
                combine(r.hdrPrefs, displayHdr) { p, display -> p to display }.collect { (p, display) ->
                    prefs = p
                    controller.hdrViewfinderAllowed = p.hdrViewfinder && display
                    refresh()
                }
            }
        }
        scope.launch { controller.previewHdr.collect { on -> _state.update { it.copy(previewHdr = on) } } }
        scope.launch { controller.state.collect { refresh() } }
    }

    /** The display can show HDR (`Display.isHdr` and `Configuration.isScreenHdr`), reported by the screen. */
    fun setDisplayHdr(hdr: Boolean) {
        displayHdr.value = hdr
    }

    /** The quick toggle: stored, then the camera reopens so the session gets (or drops) the vendor mode. */
    fun toggleSensorHdr() = scope.launch {
        val r = repo ?: return@launch
        r.setHdrPrefs(prefs.copy(sensorHdr = !prefs.sensorHdr))
        prefs = prefs.copy(sensorHdr = !prefs.sensorHdr)
        refresh()
        reopen()
    }

    private suspend fun refresh() {
        val profile = when (val s = controller.state.value) {
            is CameraState.Previewing -> s.profile
            is CameraState.Recording -> s.profile
            else -> null
        }
        val id = controller.openedCameraId
        val session = if (prefs.experimental && id != null) sessionFor(id) else null
        controller.vendorSession = session?.takeIf { prefs.sensorHdr }
        val available = matches(session, profile)
        _state.update {
            it.copy(sensorAvailable = available, sensorOn = prefs.sensorHdr && available, sensorActive = available && controller.vendorSessionActive)
        }
    }

    private suspend fun sessionFor(cameraId: String): VendorSession? {
        if (!loaded) {
            capabilities = withContext(Dispatchers.IO) {
                runCatching { AndroidCapabilities.store(application).loadFor(AndroidCapabilities.identity()) }.getOrNull()
            }
            loaded = true
        }
        return sensorSession(capabilities, cameraId)
    }

    internal companion object {
        /** The vendor session of [cameraId]'s sensor HDR mode with the modes its variant held at; null if none held (pure). */
        fun sensorSession(capabilities: DeviceCapabilities?, cameraId: String): VendorSession? {
            val caps = capabilities ?: return null
            val camera = caps.camera(cameraId)?.advertised ?: return null
            val mode = SensorHdrExtension.detect(camera) ?: return null
            val held = CapabilitySelector(caps).heldVariants(cameraId, SensorHdrExtension.MODE).map { HeldMode(it.width, it.height, it.fps) }
            return if (held.isEmpty()) null else VendorSession.of(mode, held)
        }

        fun matches(session: VendorSession?, profile: RecordingProfile?): Boolean =
            session != null && profile != null && profile.tenBit && session.allows(profile.streamWidth, profile.streamHeight, profile.fps)
    }
}
