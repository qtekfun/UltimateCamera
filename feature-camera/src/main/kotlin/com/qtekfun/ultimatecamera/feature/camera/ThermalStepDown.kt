package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import com.qtekfun.ultimatecamera.core.capabilities.CapabilitySelector
import com.qtekfun.ultimatecamera.core.capabilities.DeviceCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange
import com.qtekfun.ultimatecamera.core.capabilities.LutMode
import com.qtekfun.ultimatecamera.core.capabilities.VideoConfig
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.core.recording.probe.ProbeMapping

/**
 * Guided thermal degradation (REQ-NFR-001, D68). During a take, the first time the heat forecast says SEVERE is coming
 * (a thermal alert of [HealthForecast], or SEVERE already) the next lower mode is offered once; it is applied when
 * the take ends, if the user accepted or "Protect the phone" is on. The mode never changes mid-take. Pure; driven by
 * the camera screen at the health rate (1 Hz).
 */
class ThermalStepDown {
    private var recording = false
    private var offered = false
    private var accepted = false

    /** The lower mode offered during this take, or null. */
    var pending: RecordingProfile? = null
        private set

    /**
     * One health evaluation. Returns the mode to offer now (once per take), or null. [nextLower] is only asked when an
     * offer is due (it may read the stored probe results).
     */
    fun onHealth(recording: Boolean, thermalStatus: Int, severeSoon: Boolean, nextLower: () -> RecordingProfile?): RecordingProfile? {
        if (recording && !this.recording) {
            offered = false
            accepted = false
            pending = null
        }
        this.recording = recording
        if (!recording || offered) return null
        if (thermalStatus < PowerManager.THERMAL_STATUS_SEVERE && !severeSoon) return null
        offered = true
        pending = nextLower()
        return pending
    }

    /**
     * The user took the offer. While recording it waits for the end of the take (null); after it, the mode to switch
     * to now.
     */
    fun accept(): RecordingProfile? {
        if (recording) {
            accepted = pending != null
            return null
        }
        return pending.also { pending = null }
    }

    /** The take ended: the mode to switch to before the next one (accepted, or [automatic] "Protect the phone"), or null. */
    fun takeEnded(automatic: Boolean): RecordingProfile? {
        recording = false
        val next = pending?.takeIf { accepted || automatic }
        if (next != null) pending = null
        accepted = false
        return next
    }
}

/**
 * The next lower mode from [current] (D68): the first rung of [CapabilitySelector.degradationLadder] when this camera
 * was probed (with or without the LUT pass, same codec), otherwise the heaviest mode of [ladder] lighter than
 * [current]. Null when there is nothing lighter.
 */
object StepDownLadder {
    private const val TEN_BIT_COST = 1.25

    fun next(capabilities: DeviceCapabilities?, cameraId: String, current: RecordingProfile, ladder: List<RecordingProfile>): RecordingProfile? {
        if (capabilities != null) {
            val selector = CapabilitySelector(capabilities)
            for (lut in listOf(LutMode.HARDWARE, LutMode.NONE)) {
                val config = VideoConfig(
                    current.width,
                    current.height,
                    current.fps,
                    ProbeMapping.codecType(current.codec),
                    if (current.tenBit) DynamicRange.HLG10 else DynamicRange.STANDARD,
                    lut
                )
                selector.degradationLadder(cameraId, config).map(ProbeMapping::profile).firstOrNull { it.codec == current.codec }?.let { return it }
            }
        }
        val cost = cost(current)
        return ladder.filter { cost(it) < cost }.maxByOrNull(::cost)
    }

    /** Pixels per second weighted for 10-bit, as [VideoConfig.cost] without the LUT factor. */
    fun cost(profile: RecordingProfile): Double = profile.width.toDouble() * profile.height * profile.fps * if (profile.tenBit) TEN_BIT_COST else 1.0
}
