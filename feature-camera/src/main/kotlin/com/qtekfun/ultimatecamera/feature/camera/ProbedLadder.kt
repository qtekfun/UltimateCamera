package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import com.qtekfun.ultimatecamera.core.capabilities.AndroidCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.CapabilitySelector
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.LutMode
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.recording.probe.ProbeMapping

/**
 * The camera screen's modes from the auto-configuration results (D42): the configurations measured to hold on the
 * camera, best first, with the LUT pass (or, if only flat recording held, without it). Without results, or when nothing
 * held, the fixed [ProfileLadder] of D40.
 */
object ProbedLadder {
    fun profiles(capabilities: DeviceCapabilities?, cameraId: String): List<RecordingProfile> {
        val selector = capabilities?.let(::CapabilitySelector) ?: return emptyList()
        return selector.heldLadder(cameraId, LutMode.HARDWARE).ifEmpty { selector.heldLadder(cameraId, LutMode.NONE) }
            .map(ProbeMapping::profile)
            .distinct()
    }

    fun ladder(capabilities: DeviceCapabilities?, cameraId: String, fallback: List<RecordingProfile>): List<RecordingProfile> =
        profiles(capabilities, cameraId).ifEmpty { fallback }

    /** Reads the stored results of this phone and OS build (call off the main thread). */
    fun load(context: Context, cameraId: String, fallback: List<RecordingProfile>): List<RecordingProfile> {
        val stored = runCatching { AndroidCapabilities.store(context).loadFor(AndroidCapabilities.identity()) }.getOrNull()
        return ladder(stored, cameraId, fallback)
    }
}
