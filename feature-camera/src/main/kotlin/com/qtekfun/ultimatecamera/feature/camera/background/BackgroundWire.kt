package com.qtekfun.ultimatecamera.feature.camera.background

import android.content.Context
import android.util.Log
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Glue between the camera screen and [RecordingService] (D66): feeds [BackgroundPolicy] with the take, the camera role
 * and the setting, starts and stops the service, keeps the [TakeMarker] and publishes the notification's status. Every
 * call comes from the view model's (main) scope.
 *
 * @param onIdleHidden off screen, nothing needs the camera any more: the screen closes it (as on ON_STOP before D66).
 */
class BackgroundWire(
    private val context: Context,
    settings: SettingsRepository,
    scope: CoroutineScope,
    camera: Flow<CameraState>,
    roleActive: Flow<Boolean>,
    status: Flow<BackgroundStatus>,
    private val onIdleHidden: () -> Unit,
    onStopRequested: () -> Unit
) {
    private val policy = BackgroundPolicy()
    private val marker = TakeMarker(File(context.filesDir, TakeMarker.FILE_NAME))

    /** The take that was being written when the process last died (or crashed), once per process; else null. */
    val interruptedTake: String? = TakeMarker.consumeOncePerProcess(marker)

    val visible: Boolean get() = policy.visible

    init {
        RecordingServiceBridge.onStop = onStopRequested
        RecordingServiceBridge.onLost = { scope.launch { apply(policy.serviceLost()) } }
        scope.launch { settings.settings.map { it.backgroundRecording }.distinctUntilChanged().collect { apply(policy.setEnabled(it)) } }
        scope.launch {
            camera.collect { state ->
                withContext(Dispatchers.IO) { if (state is CameraState.Recording) marker.begin(state.fileName) else marker.end() }
                apply(policy.recordingChanged(state is CameraState.Recording))
            }
        }
        scope.launch { roleActive.distinctUntilChanged().collect { apply(policy.roleChanged(it)) } }
        scope.launch { status.collect(RecordingServiceBridge::publish) }
    }

    /** ON_STOP: true when the camera is kept running off screen, false when the screen must close it (D40). */
    fun hidden(): Boolean {
        val step = policy.hidden()
        // CLOSE here is the caller's job (it closes on false), not onIdleHidden's.
        apply(step.copy(camera = CameraChange.NONE))
        return step.camera == CameraChange.KEEP
    }

    /** ON_START: true when the camera was kept running and only needs its preview again. */
    fun shown(): Boolean {
        val step = policy.shown()
        apply(step)
        return step.camera == CameraChange.RESUME
    }

    /** The screen closed the camera itself (left for another screen). */
    fun left() = policy.left()

    /** A guard (D47) stopped the take: off screen the user learns it from a notification. */
    fun guardStopped(text: String) {
        if (!policy.visible) RecordingService.notifyStopped(context, text)
    }

    /** The view model is gone and its take closed (any thread). */
    fun shutdown() {
        marker.end()
        RecordingServiceBridge.onStop = null
        RecordingServiceBridge.onLost = null
        RecordingServiceBridge.publish(BackgroundStatus())
        RecordingService.stop(context)
    }

    @Suppress("TooGenericExceptionCaught") // ForegroundServiceStartNotAllowedException and friends.
    private fun apply(step: BackgroundStep) {
        when (step.service) {
            ServiceChange.START -> try {
                RecordingService.start(context)
            } catch (e: RuntimeException) {
                Log.w(TAG, "foreground service refused: ${e.message}")
                apply(policy.serviceLost())
            }
            ServiceChange.STOP -> RecordingService.stop(context)
            ServiceChange.NONE -> Unit
        }
        if (step.camera == CameraChange.CLOSE) onIdleHidden()
    }

    private companion object {
        const val TAG = "BackgroundWire"
    }
}
