package com.qtekfun.ultimatecamera.feature.multicam.monitor

import com.qtekfun.ultimatecamera.core.net.control.CameraStatus
import com.qtekfun.ultimatecamera.core.net.control.CaptureSettings
import com.qtekfun.ultimatecamera.core.net.control.ChoiceInfo
import com.qtekfun.ultimatecamera.core.net.control.ErrorCode
import com.qtekfun.ultimatecamera.core.net.control.HealthAlertKind
import com.qtekfun.ultimatecamera.core.net.control.RecordingState
import com.qtekfun.ultimatecamera.core.net.control.StatusWarning
import com.qtekfun.ultimatecamera.core.net.control.ThermalLevel
import com.qtekfun.ultimatecamera.core.net.role.LinkState
import com.qtekfun.ultimatecamera.feature.camera.AlertLevel
import com.qtekfun.ultimatecamera.feature.camera.HealthAlert
import com.qtekfun.ultimatecamera.feature.camera.HealthKind
import com.qtekfun.ultimatecamera.feature.camera.ui.Tone
import com.qtekfun.ultimatecamera.feature.camera.ui.formatTimecode

/** The control link as the monitor shows it (REQ-NET-007). */
enum class LinkUi {
    /** First dial since the screen opened: nothing known about the camera yet. */
    CONNECTING,

    /** Connected; the status on screen is the camera's current one. */
    LIVE,

    /** The link dropped and is being redialled; the last known state stays on screen, marked as such. */
    RETRYING,

    /** Not dialling (stopped, or this phone was unpaired by the camera). */
    OFFLINE
}

/** The REC button: the camera's confirmed state, or an optimistic transition while a command waits for its answer. */
enum class RecUi {
    IDLE,

    /** Start sent, not confirmed yet. */
    STARTING,

    /** The camera accepted a start at a common time that has not come yet. */
    SCHEDULED,
    RECORDING,

    /** Stop sent, not confirmed yet. */
    STOPPING,
    ERROR;

    /** What the button draws: recording (square) or not (circle). */
    val showsRecording: Boolean get() = this == RECORDING || this == STARTING || this == SCHEDULED

    val pending: Boolean get() = this == STARTING || this == STOPPING
}

/** A REC or STOP sent by this controller and not answered yet. */
data class RecIntent(val start: Boolean, val commandId: String)

/** Everything the controller knows about one camera at one instant: the input of [RemoteCameraMapper.map]. */
data class RemoteSnapshot(
    val id: String,
    val name: String,
    val link: LinkState = LinkState.STOPPED,
    val status: CameraStatus? = null,
    val everConnected: Boolean = false,
    val recIntent: RecIntent? = null,
    /** Last refusal or failure of a command, shown translated until the next command. */
    val lastError: ErrorCode? = null,
    val rttNanos: Long? = null
)

/** Capture values for the chips, already formatted where a format is universal. Null value: unknown. */
data class CaptureUi(
    val autoExposure: Boolean,
    val iso: Int?,
    val exposureNanos: Long?,
    val shutter: String?,
    val autoFocus: Boolean,
    val focusDiopters: Float?,
    val autoWhiteBalance: Boolean,
    val kelvin: Int?
)

/** One camera as the monitor and master screens draw it. Pure data; strings are resolved by the UI. */
data class RemoteCameraUi(
    val id: String,
    val name: String,
    val link: LinkUi,
    /** True when what is shown is the last known state of a camera not reachable right now. */
    val stale: Boolean,
    val rec: RecUi,
    val timecode: String,
    val profileLabel: String?,
    val codec: String?,
    val fps: Int,
    val storageMinutes: Int?,
    val storageTone: Tone,
    val batteryPercent: Int?,
    val charging: Boolean,
    val batteryTone: Tone,
    /** Battery temperature in °C with one decimal ("38.4"); null when not reported. */
    val temperatureC: String?,
    val temperatureTone: Tone,
    val droppedFrames: Long?,
    val droppedTone: Tone,
    /** Predictive alerts in the camera banner's own type, so [com.qtekfun.ultimatecamera.feature.camera.HealthBanner] draws them. */
    val alerts: List<HealthAlert>,
    /** Camera warnings the alerts do not already say (clock not synced, recording error, a low level without forecast). */
    val notices: List<StatusWarning>,
    val capture: CaptureUi,
    val profileId: String?,
    val lutId: String?,
    val profiles: List<ChoiceInfo>,
    val luts: List<ChoiceInfo>,
    val error: ErrorCode?
) {
    val hasCritical: Boolean get() = alerts.any { it.level == AlertLevel.CRITICAL }

    /** Controls are usable while the link is live; commands sent while retrying are queued by the link anyway. */
    val controlsEnabled: Boolean get() = link == LinkUi.LIVE || link == LinkUi.RETRYING
}

/** Status to UI mapping (pure, unit-tested). */
object RemoteCameraMapper {
    const val DEFAULT_FPS = 30
    private const val STORAGE_WARN_MIN = 10
    private const val STORAGE_ALERT_MIN = 2
    private const val BATTERY_WARN_PERCENT = 20
    private const val BATTERY_ALERT_PERCENT = 10
    private const val NANOS_PER_MS = 1_000_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val TENTHS = 10

    /**
     * @param nowCommonNanos the common clock now, when this phone has one; the take time then matches the camera's.
     * @param recordingSeenSinceMs local time (ms) this controller first saw the current take, the fallback base of the
     *   take time without a common clock; [nowMs] is the same local clock.
     */
    fun map(snapshot: RemoteSnapshot, nowCommonNanos: Long? = null, recordingSeenSinceMs: Long? = null, nowMs: Long = 0L): RemoteCameraUi {
        val status = snapshot.status
        val health = status?.health
        val link = linkUi(snapshot.link, snapshot.everConnected)
        val profile = status?.profiles?.firstOrNull { it.id == status.profileId }
        val fps = profile?.fps?.takeIf { it > 0 } ?: DEFAULT_FPS
        val storage = health?.storageMinutes ?: status?.freeSpaceMinutes
        val alerts = health?.alerts.orEmpty().map { HealthAlert(kind(it.kind), if (it.critical) AlertLevel.CRITICAL else AlertLevel.WARNING, it.minutes) }
        val rec = recUi(status?.recording, snapshot.recIntent)
        val elapsedMs = if (status?.recording == RecordingState.RECORDING) elapsedMs(status, nowCommonNanos, recordingSeenSinceMs, nowMs) else 0L
        return RemoteCameraUi(
            id = snapshot.id,
            name = snapshot.name,
            link = link,
            stale = status != null && link != LinkUi.LIVE,
            rec = rec,
            timecode = formatTimecode(elapsedMs, fps),
            profileLabel = profile?.label ?: status?.profileId,
            codec = profile?.codec,
            fps = fps,
            storageMinutes = storage,
            storageTone = lowTone(storage, STORAGE_WARN_MIN, STORAGE_ALERT_MIN),
            batteryPercent = status?.batteryPercent,
            charging = status?.charging == true,
            batteryTone = if (status?.charging == true) Tone.NORMAL else lowTone(status?.batteryPercent, BATTERY_WARN_PERCENT, BATTERY_ALERT_PERCENT),
            temperatureC = health?.batteryTempTenthsC?.let { "%d.%d".format(it / TENTHS, kotlin.math.abs(it % TENTHS)) },
            temperatureTone = thermalTone(status?.thermal ?: ThermalLevel.NONE),
            droppedFrames = status?.droppedFrames,
            droppedTone = if ((status?.droppedFrames ?: 0L) > 0L) Tone.ALERT else Tone.NORMAL,
            alerts = alerts,
            notices = notices(status?.warnings.orEmpty(), alerts),
            capture = captureUi(status?.capture ?: CaptureSettings()),
            profileId = status?.profileId,
            lutId = status?.lutId,
            profiles = status?.profiles.orEmpty(),
            luts = status?.luts.orEmpty(),
            error = snapshot.lastError
        )
    }

    fun linkUi(state: LinkState, everConnected: Boolean): LinkUi = when (state) {
        LinkState.CONNECTED -> LinkUi.LIVE
        LinkState.STOPPED -> LinkUi.OFFLINE
        LinkState.WAITING, LinkState.CONNECTING -> if (everConnected) LinkUi.RETRYING else LinkUi.CONNECTING
    }

    /** The pending command wins until the camera's status reaches what it asked for (then the intent is dropped). */
    fun recUi(recording: RecordingState?, intent: RecIntent?): RecUi {
        val confirmed = when (recording) {
            RecordingState.RECORDING -> RecUi.RECORDING
            RecordingState.SCHEDULED -> RecUi.SCHEDULED
            RecordingState.ERROR -> RecUi.ERROR
            RecordingState.IDLE, null -> RecUi.IDLE
        }
        return when {
            intent == null -> confirmed
            intent.start && !confirmed.showsRecording -> RecUi.STARTING
            !intent.start && confirmed.showsRecording -> RecUi.STOPPING
            else -> confirmed
        }
    }

    /** True when [status] already shows what [intent] asked for, so the optimistic state can go. */
    fun intentReached(status: CameraStatus?, intent: RecIntent): Boolean {
        val recording = status?.recording == RecordingState.RECORDING || status?.recording == RecordingState.SCHEDULED
        return recording == intent.start
    }

    private fun elapsedMs(status: CameraStatus, nowCommonNanos: Long?, seenSinceMs: Long?, nowMs: Long): Long {
        val start = status.recordingStartCommonNanos
        return when {
            start != null && nowCommonNanos != null -> (nowCommonNanos - start) / NANOS_PER_MS
            seenSinceMs != null -> nowMs - seenSinceMs
            else -> 0L
        }.coerceAtLeast(0L)
    }

    private fun notices(warnings: Set<StatusWarning>, alerts: List<HealthAlert>): List<StatusWarning> {
        val covered = alerts.map { it.kind }.toSet()
        return StatusWarning.entries.filter { it in warnings }.filterNot {
            (it == StatusWarning.BATTERY_LOW && HealthKind.BATTERY in covered) ||
                (it == StatusWarning.STORAGE_LOW && HealthKind.STORAGE in covered) ||
                (it == StatusWarning.THERMAL && HealthKind.THERMAL in covered)
        }
    }

    private fun lowTone(value: Int?, warnBelow: Int, alertBelow: Int): Tone = when {
        value == null -> Tone.NORMAL
        value < alertBelow -> Tone.ALERT
        value < warnBelow -> Tone.WARNING
        else -> Tone.NORMAL
    }

    fun thermalTone(level: ThermalLevel): Tone = when {
        level >= ThermalLevel.SEVERE -> Tone.ALERT
        level >= ThermalLevel.MODERATE -> Tone.WARNING
        else -> Tone.NORMAL
    }

    private fun captureUi(c: CaptureSettings): CaptureUi = CaptureUi(
        autoExposure = c.autoExposure,
        iso = c.iso,
        exposureNanos = c.exposureTimeNanos,
        shutter = c.exposureTimeNanos?.let { shutterText(it) },
        autoFocus = c.autoFocus,
        focusDiopters = c.focusDiopters,
        autoWhiteBalance = c.autoWhiteBalance,
        kelvin = c.whiteBalanceKelvin
    )

    /** 1/50 for fractions of a second, 0.5" / 2" for long exposures, like a camera's shutter readout. */
    fun shutterText(nanos: Long): String = if (nanos >= NANOS_PER_SECOND / 2) {
        val tenths = nanos * TENTHS / NANOS_PER_SECOND
        if (tenths % TENTHS == 0L) "${tenths / TENTHS}\"" else "${tenths / TENTHS}.${tenths % TENTHS}\""
    } else {
        "1/${(NANOS_PER_SECOND + nanos / 2) / nanos.coerceAtLeast(1)}"
    }
}

private fun kind(kind: HealthAlertKind): HealthKind = when (kind) {
    HealthAlertKind.BATTERY -> HealthKind.BATTERY
    HealthAlertKind.STORAGE -> HealthKind.STORAGE
    HealthAlertKind.THERMAL -> HealthKind.THERMAL
}
