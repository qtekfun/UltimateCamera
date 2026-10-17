package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.camera.CameraControls
import com.qtekfun.ultimatecamera.core.camera.ControlMath
import com.qtekfun.ultimatecamera.core.camera.ControlRanges
import com.qtekfun.ultimatecamera.core.camera.LiveExposure
import com.qtekfun.ultimatecamera.core.recording.CameraController
import kotlinx.coroutines.flow.StateFlow

/** The camera screen's manual controls (REQ-CAM-002): what the panel reads and the changes it makes. */
class ManualControls internal constructor(private val controller: CameraController) {
    val controls: StateFlow<CameraControls> get() = controller.controls
    val ranges: StateFlow<ControlRanges?> get() = controller.ranges
    val live: StateFlow<LiveExposure> get() = controller.live
    val previewRotation: Int get() = controller.previewRotationDegrees

    fun set(controls: CameraControls) = controller.setControls(controls)

    /** Tap (focus and meter there) or long press ([lock]: also lock AE and AF) on the preview. */
    fun meterAt(x: Float, y: Float, viewWidth: Int, viewHeight: Int, lock: Boolean) = controller.meterAt(x, y, viewWidth, viewHeight, lock)

    /** Back to the whole picture, unlocked. */
    fun clearPoint() = set(controls.value.copy(meteringPoint = null, afLock = false, aeLock = false))
}

/** Pure rules of the panel, kept out of Compose so they are unit-tested. */
internal object ManualRules {
    /**
     * Manual exposure starting from what auto exposure is doing now ([live]), so switching to manual does not jump;
     * without live values the 180° shutter and the current ISO are used.
     */
    fun toManualExposure(c: CameraControls, live: LiveExposure, fps: Int): CameraControls = if (c.manualExposure) {
        c
    } else {
        c.copy(manualExposure = true, iso = live.iso ?: c.iso, exposureTimeNs = live.exposureTimeNs ?: ControlMath.shutter180Ns(fps), aeLock = false)
    }

    /** Manual focus starting where autofocus is now. */
    fun toManualFocus(c: CameraControls, live: LiveExposure): CameraControls =
        if (c.manualFocus) c else c.copy(manualFocus = true, focusDiopters = live.focusDiopters ?: c.focusDiopters, afLock = false)

    /** The stop index one step from [index] in a list of [size] stops. */
    fun step(index: Int, delta: Int, size: Int): Int = (index + delta).coerceIn(0, (size - 1).coerceAtLeast(0))
}
