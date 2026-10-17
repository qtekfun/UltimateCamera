package com.qtekfun.ultimatecamera.feature.camera.remote

import com.qtekfun.ultimatecamera.core.camera.CameraControls
import com.qtekfun.ultimatecamera.core.camera.ControlRanges
import com.qtekfun.ultimatecamera.core.camera.LiveExposure
import com.qtekfun.ultimatecamera.core.camera.WhiteBalance
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.luts.LutEntry
import com.qtekfun.ultimatecamera.core.net.control.CaptureModeWire
import com.qtekfun.ultimatecamera.core.net.control.CaptureSettings
import com.qtekfun.ultimatecamera.core.net.control.ChoiceInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import com.qtekfun.ultimatecamera.feature.camera.CaptureMode
import com.qtekfun.ultimatecamera.feature.camera.MonitorLook
import com.qtekfun.ultimatecamera.feature.camera.RecordingLook
import kotlin.math.roundToInt

/** What a remote command asks the camera screen to do (D56). */
sealed interface RemoteAction {
    /** New manual controls for `CameraController.setControls` (clamped there to the camera's ranges). */
    data class Controls(val controls: CameraControls) : RemoteAction

    /** Open the profile [key] (switching codec first when it differs from the current one). */
    data class Profile(val key: ProfileKey) : RemoteAction

    data class Recording(val look: RecordingLook) : RemoteAction

    data class Monitor(val look: MonitorLook) : RemoteAction

    data class Mode(val mode: CaptureMode) : RemoteAction

    data class Slave(val enabled: Boolean) : RemoteAction

    /** The value does not fit this command ([reason] goes back in the error detail). */
    data class Invalid(val reason: String) : RemoteAction

    /** This camera cannot do it (e.g. no manual exposure). */
    data class Unsupported(val reason: String) : RemoteAction
}

/** A recording profile as the protocol names it: `3840x2160@30-10-HEVC` (size, fps, bit depth, codec). */
data class ProfileKey(val width: Int, val height: Int, val fps: Int, val tenBit: Boolean, val codec: VideoCodec) {
    val id: String get() = "${width}x$height@$fps-${if (tenBit) TEN else EIGHT}-${codec.name}"

    fun matches(profile: RecordingProfile): Boolean =
        profile.width == width && profile.height == height && profile.fps == fps && profile.tenBit == tenBit && profile.codec == codec

    companion object {
        private const val TEN = "10"
        private const val EIGHT = "8"
        private const val GROUP_WIDTH = 1
        private const val GROUP_HEIGHT = 2
        private const val GROUP_FPS = 3
        private const val GROUP_BITS = 4
        private const val GROUP_CODEC = 5
        private val PATTERN = Regex("""(\d{2,5})x(\d{2,5})@(\d{1,3})-(8|10)-([A-Z0-9]+)""")

        fun of(profile: RecordingProfile) = ProfileKey(profile.width, profile.height, profile.fps, profile.tenBit, profile.codec)

        fun parse(id: String): ProfileKey? {
            val g = PATTERN.matchEntire(id)?.groupValues ?: return null
            val videoCodec = VideoCodec.entries.firstOrNull { it.name == g[GROUP_CODEC] } ?: return null
            return ProfileKey(g[GROUP_WIDTH].toInt(), g[GROUP_HEIGHT].toInt(), g[GROUP_FPS].toInt(), g[GROUP_BITS] == TEN, videoCodec)
        }
    }
}

/** Pure mapping between the control protocol and the camera screen, unit-tested (D56). */
object RemoteCommands {
    /** What [body] asks for, given the [current] controls and the open camera's [ranges] (null while closed). */
    @Suppress("CyclomaticComplexMethod")
    fun map(body: CommandBody, current: CameraControls, ranges: ControlRanges?): RemoteAction = when (body) {
        is CommandBody.SetExposure -> exposure(body, current, ranges)
        is CommandBody.SetFocus -> when {
            body.auto -> RemoteAction.Controls(current.copy(manualFocus = false, afLock = false))
            ranges != null && !ranges.manualFocus -> RemoteAction.Unsupported("manual focus")
            else -> RemoteAction.Controls(current.copy(manualFocus = true, focusDiopters = body.distanceDiopters ?: current.focusDiopters, afLock = false))
        }
        is CommandBody.SetWhiteBalance -> when {
            body.auto -> RemoteAction.Controls(current.copy(whiteBalance = WhiteBalance.AUTO))
            ranges != null && ranges.kelvinRange == null -> RemoteAction.Unsupported("manual white balance")
            else -> RemoteAction.Controls(current.copy(whiteBalance = WhiteBalance.MANUAL, kelvin = body.kelvin ?: current.kelvin, awbLock = false))
        }
        is CommandBody.SetLocks -> RemoteAction.Controls(current.copy(aeLock = body.aeLock ?: current.aeLock, awbLock = body.awbLock ?: current.awbLock))
        is CommandBody.SelectProfile -> ProfileKey.parse(body.profileId)?.let { RemoteAction.Profile(it) } ?: RemoteAction.Invalid("profile")
        is CommandBody.SelectLut -> recordingLook(body.lutId)?.let { RemoteAction.Recording(it) } ?: RemoteAction.Invalid("look")
        is CommandBody.SetMonitorLook -> monitorLook(body.token)?.let { RemoteAction.Monitor(it) } ?: RemoteAction.Invalid("monitor look")
        is CommandBody.SetCaptureMode -> RemoteAction.Mode(if (body.mode == CaptureModeWire.PHOTO) CaptureMode.PHOTO else CaptureMode.VIDEO)
        is CommandBody.SetSlaveMode -> RemoteAction.Slave(body.enabled)
        // Recording and status requests are handled by CameraNode itself.
        is CommandBody.StartRecording, is CommandBody.StopRecording, CommandBody.RequestStatus -> RemoteAction.Invalid("not a setting")
    }

    private fun exposure(body: CommandBody.SetExposure, current: CameraControls, ranges: ControlRanges?): RemoteAction {
        if (!body.auto) {
            if (ranges != null && !ranges.manualExposure) return RemoteAction.Unsupported("manual exposure")
            return RemoteAction.Controls(
                current.copy(
                    manualExposure = true,
                    iso = body.iso ?: current.iso,
                    exposureTimeNs = body.exposureTimeNanos ?: current.exposureTimeNs,
                    aeLock = false
                )
            )
        }
        val steps = body.evCompensation?.let { ev ->
            val step = ranges?.evStep?.takeIf { it > 0f } ?: DEFAULT_EV_STEP
            val raw = (ev / step).roundToInt()
            ranges?.let { raw.coerceIn(it.evRange) } ?: raw
        } ?: current.exposureCompensation
        return RemoteAction.Controls(current.copy(manualExposure = false, exposureCompensation = steps))
    }

    /** A recording look token as the protocol carries it: null is the camera's own signal; unknown tokens are refused. */
    fun recordingLook(token: String?): RecordingLook? {
        val look = RecordingLook.parse(token)
        return look.takeIf { token == null || it.token == token }
    }

    fun monitorLook(token: String?): MonitorLook? {
        val look = MonitorLook.parse(token)
        return look.takeIf { token == null || it.token == token }
    }

    /** Capture settings for the status: the requested mode with the live values the camera reports (also in auto). */
    fun captureSettings(controls: CameraControls, live: LiveExposure, ranges: ControlRanges?): CaptureSettings = CaptureSettings(
        autoExposure = !controls.manualExposure,
        iso = live.iso ?: controls.iso.takeIf { controls.manualExposure },
        exposureTimeNanos = live.exposureTimeNs ?: controls.exposureTimeNs.takeIf { controls.manualExposure },
        autoFocus = !controls.manualFocus,
        focusDiopters = live.focusDiopters ?: controls.focusDiopters.takeIf { controls.manualFocus },
        autoWhiteBalance = controls.whiteBalance == WhiteBalance.AUTO,
        whiteBalanceKelvin = controls.kelvin.takeIf { controls.whiteBalance == WhiteBalance.MANUAL },
        evCompensation = controls.exposureCompensation * (ranges?.evStep ?: DEFAULT_EV_STEP),
        aeLock = controls.aeLock,
        awbLock = controls.awbLock
    )

    /** A profile for the monitor's picker (D57): protocol id, the camera's own label, codec and fps. */
    fun profileChoice(profile: RecordingProfile): ChoiceInfo = ChoiceInfo(ProfileKey.of(profile).id, profile.label, profile.codec.name, profile.fps)

    /**
     * Recording looks for the monitor's picker (D57), by the token [CommandBody.SelectLut] carries: UC-Log on 10-bit
     * modes, then the library. "None" is the null choice the monitor always offers.
     */
    fun lutChoices(library: List<LutEntry>, tenBit: Boolean): List<ChoiceInfo> =
        listOfNotNull(ChoiceInfo(RecordingLook.UcLog.token, UC_LOG_LABEL).takeIf { tenBit }) +
            library.map { ChoiceInfo(RecordingLook.Library(it.id).token, it.title) }

    fun wire(mode: CaptureMode): CaptureModeWire = if (mode == CaptureMode.PHOTO) CaptureModeWire.PHOTO else CaptureModeWire.VIDEO

    private const val DEFAULT_EV_STEP = 1f / 3
    private const val UC_LOG_LABEL = "UC-Log"
}
