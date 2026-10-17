package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import android.os.PowerManager
import com.qtekfun.ultimatecamera.core.capabilities.AndroidCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorSettings
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.recording.PhoneTier
import com.qtekfun.ultimatecamera.core.recording.PhoneTierClassifier
import com.qtekfun.ultimatecamera.core.recording.PhoneTraitsReader
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Performance and heat on the camera screen (REQ-NFR-001, D68):
 * - the phone's [PhoneTier], from RAM at once and refined with the GL renderer and the 4K30 encoder once a camera has
 *   opened; a modest phone gets the lighter [PreviewLoad] and the 540p-class proxy;
 * - the preview load follows the heat ([PreviewLoadPolicy]): half rate, 1280 px and no scope when hot;
 * - the guided step down ([ThermalStepDown]): offered once per take when SEVERE is forecast, applied when the take ends.
 *
 * [monitor] is the entry for the monitoring tools (instead of the controller), so the load can thin or hide the scope.
 * [profile] gives the current mode and [ladder] the screen's modes; [offer] shows a message, [apply] switches mode.
 */
class PerformanceWire(
    private val context: Context,
    private val settings: SettingsRepository,
    private val controller: CameraController,
    private val scope: CoroutineScope,
    private val profile: () -> RecordingProfile?,
    private val ladder: () -> List<RecordingProfile>,
    private val offer: (CameraMessage) -> Unit,
    private val apply: (RecordingProfile) -> Unit
) {
    private val _tier = MutableStateFlow(if (controller.modestPhone) PhoneTier.MODEST else PhoneTier.CAPABLE)
    val tier: StateFlow<PhoneTier> = _tier.asStateFlow()

    private val _load = MutableStateFlow(PreviewLoadPolicy.load(_tier.value, PowerManager.THERMAL_STATUS_NONE, severeSoon = false))

    /** What the preview may cost now; the preview view caps its surface by [PreviewLoad.maxLongSide]. */
    val load: StateFlow<PreviewLoad> = _load.asStateFlow()

    private var requested = MonitorSettings.OFF
    private var tierRefined = false
    private val stepDown = ThermalStepDown()
    private var wasRecording = false
    private var lastHealth = HealthState()

    init {
        applyLoad(_load.value)
        scope.launch { controller.state.collect(::onCamera) }
    }

    /** The monitoring tools asked for; the renderer gets them under the current load. */
    fun monitor(settings: MonitorSettings) {
        requested = settings
        controller.setMonitorSettings(_load.value.applyTo(settings))
    }

    /** One health evaluation (1 Hz): preview load and the step-down offer. */
    fun onHealth(health: HealthState) {
        lastHealth = health
        val severeSoon = PreviewLoadPolicy.severeSoon(health)
        updateLoad(PreviewLoadPolicy.load(_tier.value, health.thermalStatus, severeSoon))
        val recording = controller.state.value is CameraState.Recording
        val current = profile() ?: return
        val offered = stepDown.onHealth(recording, health.thermalStatus, severeSoon) { nextLower(current) }
        if (offered != null) offer(CameraMessage.StepDownOffer(current.label, offered.label))
    }

    /** The user took the step-down offer: applied at the end of the take (or now, if it already ended). */
    fun acceptStepDown() {
        stepDown.accept()?.let(::switchTo)
    }

    private fun onCamera(camera: CameraState) {
        val recording = camera is CameraState.Recording
        if (wasRecording && !recording) scope.launch { takeEnded() }
        if (!wasRecording && recording) scope.launch { loadProbed() }
        wasRecording = recording
        if (camera is CameraState.Previewing && !tierRefined) {
            tierRefined = true
            scope.launch { refineTier() }
        }
    }

    private suspend fun takeEnded() {
        val automatic = settings.settings.first().thermalStepDown
        stepDown.takeEnded(automatic)?.let(::switchTo)
    }

    private fun switchTo(next: RecordingProfile) {
        val from = profile()?.label ?: return
        apply(next)
        offer(CameraMessage.SteppedDown(from, next.label))
    }

    private fun nextLower(current: RecordingProfile): RecordingProfile? {
        val id = controller.openedCameraId ?: return null
        return StepDownLadder.next(probed, id, current, ladder())
    }

    /** The stored probe results, read off the main thread at the start of each take (a small JSON file). */
    @Volatile private var probed: DeviceCapabilities? = null

    private suspend fun loadProbed() {
        probed = withContext(Dispatchers.IO) {
            runCatching { AndroidCapabilities.store(context).loadFor(AndroidCapabilities.identity()) }.getOrNull()
        }
    }

    private suspend fun refineTier() {
        val renderer = controller.glRenderer
        val traits = withContext(Dispatchers.Default) { PhoneTraitsReader.read(context, renderer) }
        val tier = PhoneTierClassifier.classify(traits)
        controller.modestPhone = tier == PhoneTier.MODEST
        _tier.value = tier
        updateLoad(PreviewLoadPolicy.load(tier, lastHealth.thermalStatus, PreviewLoadPolicy.severeSoon(lastHealth)))
    }

    private fun updateLoad(load: PreviewLoad) {
        if (load == _load.value) return
        _load.value = load
        applyLoad(load)
    }

    private fun applyLoad(load: PreviewLoad) {
        controller.previewEveryNthFrame = load.everyNthFrame
        controller.setMonitorSettings(load.applyTo(requested))
    }
}
