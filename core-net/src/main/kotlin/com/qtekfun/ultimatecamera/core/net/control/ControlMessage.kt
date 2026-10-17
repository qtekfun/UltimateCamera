package com.qtekfun.ultimatecamera.core.net.control

import com.qtekfun.ultimatecamera.core.net.monitor.SignallingMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Messages of the authenticated control channel, after the handshake (see `Handshake`). Each is one JSON object with
 * a `type` discriminator, sent as one encrypted, length-prefixed frame (`FrameCipher`).
 *
 * Times named `...CommonNanos` are on the common clock: nanoseconds since the Unix epoch as kept by the clock source
 * (the master, see `CommonClock`). Times named `...Nanos` without "Common" are the sender's monotonic clock and are only
 * ever compared with that same clock.
 *
 * Compatibility (protocol [ControlProtocol.VERSION]): unknown fields are ignored; an unknown `type` is answered with
 * [Error] code [ErrorCode.UNKNOWN_MESSAGE] and the connection stays up; removing or changing a field's meaning needs a
 * new protocol version, negotiated in the handshake.
 */
@Serializable
sealed interface ControlMessage {
    /**
     * Liveness, both directions, every [ControlProtocol.HEARTBEAT_INTERVAL_MS]. It also measures the control round trip
     * without extra messages: [echoNanos] repeats the peer's last [sentNanos] and [echoHeldNanos] says how long ago
     * it arrived, so the peer gets `rtt = now − echoNanos − echoHeldNanos` on its own clock.
     */
    @Serializable
    @SerialName("heartbeat")
    data class Heartbeat(val sentNanos: Long, val echoNanos: Long? = null, val echoHeldNanos: Long? = null) : ControlMessage

    /** Camera to controllers: full state, sent on connect (resync) and on every change. */
    @Serializable
    @SerialName("status")
    data class Status(val status: CameraStatus) : ControlMessage

    /** Controller to camera. [id] is unique per command (a UUID); a repeated id is not executed twice. */
    @Serializable
    @SerialName("command")
    data class Command(val id: String, val body: CommandBody) : ControlMessage

    /** The command [commandId] was applied (or had already been: [duplicate]). */
    @Serializable
    @SerialName("ack")
    data class Ack(val commandId: String, val duplicate: Boolean = false, val detail: AckDetail? = null) : ControlMessage

    /** A command failed or a message was refused. [commandId] is null for errors not tied to a command. */
    @Serializable
    @SerialName("error")
    data class Error(val commandId: String?, val code: ErrorCode, val detail: String? = null) : ControlMessage

    /** Camera to controllers: a take is closed on disk; the base for the later upload to the master (REQ-NET-006). */
    @Serializable
    @SerialName("recording_finished")
    data class RecordingFinished(val clip: ClipInfo) : ControlMessage

    /** Orderly close; the peer does not reconnect for [reason] [ByeReason.UNPAIRED]. */
    @Serializable
    @SerialName("bye")
    data class Bye(val reason: ByeReason) : ControlMessage

    /**
     * Optional features of the sender ([ControlFeatures]), both directions, as the very first message after the handshake
     * (D70). Added in protocol v1: an older peer answers it with [Error] [ErrorCode.UNKNOWN_MESSAGE] and is treated as
     * having no optional feature, as is a peer whose first message is anything else.
     */
    @Serializable
    @SerialName("features")
    data class Features(val features: Set<String>) : ControlMessage

    /**
     * WebRTC signalling of the monitor video (hello, offer, answer, ICE, bye) carried inside this authenticated,
     * encrypted channel (D70). Only sent to a peer that advertised [ControlFeatures.SIGNALLING]. The controller opens a
     * video session with a [SignallingMessage.Hello]; the camera answers with its hello and the offer.
     */
    @Serializable
    @SerialName("signal")
    data class Signal(val message: SignallingMessage) : ControlMessage
}

/** Names of the optional features exchanged in [ControlMessage.Features]. */
object ControlFeatures {
    /** WebRTC signalling inside the control channel ([ControlMessage.Signal], D70). */
    const val SIGNALLING = "webrtc_signalling"

    /** What this build supports. */
    val LOCAL: Set<String> = setOf(SIGNALLING)
}

/** Commands a controller (master or monitor) sends to a camera. All are idempotent by their recording id or value. */
@Serializable
sealed interface CommandBody {
    /**
     * Start take [recordingId] (unique, e.g. a UUID shared by every camera of one take). With [startAtCommonNanos] the
     * camera starts when its estimate of the common clock reaches it (REQ-NET-005); null starts at once. A start for a
     * recording id the camera has already started is acknowledged as a duplicate without effect, so a command replayed
     * after a reconnection never starts a second take.
     */
    @Serializable
    @SerialName("start_recording")
    data class StartRecording(val recordingId: String, val startAtCommonNanos: Long? = null) : CommandBody

    /** Stop [recordingId] (null: whatever is recording or scheduled). Stopping what is already stopped is acknowledged. */
    @Serializable
    @SerialName("stop_recording")
    data class StopRecording(val recordingId: String? = null, val stopAtCommonNanos: Long? = null) : CommandBody

    /** Auto exposure, or manual ISO and exposure time. [evCompensation] applies in auto. */
    @Serializable
    @SerialName("set_exposure")
    data class SetExposure(val auto: Boolean, val iso: Int? = null, val exposureTimeNanos: Long? = null, val evCompensation: Float? = null) : CommandBody

    /** Continuous autofocus, or a manual focus distance in diopters (0 = infinity). */
    @Serializable
    @SerialName("set_focus")
    data class SetFocus(val auto: Boolean, val distanceDiopters: Float? = null) : CommandBody

    /** Auto white balance, or a manual colour temperature in kelvin. */
    @Serializable
    @SerialName("set_white_balance")
    data class SetWhiteBalance(val auto: Boolean, val kelvin: Int? = null, val tint: Float? = null) : CommandBody

    /** Apply recording profile [profileId] (resolution, fps, codec, curve/LUT, audio, lens). Refused while recording. */
    @Serializable
    @SerialName("select_profile")
    data class SelectProfile(val profileId: String) : CommandBody

    /**
     * The look baked into the recording, as the camera's look token (`uclog`, `lib:<library id>`); null records the
     * camera's own signal. A change during a take applies from the next take.
     */
    @Serializable
    @SerialName("select_lut")
    data class SelectLut(val lutId: String?) : CommandBody

    /**
     * Look shown on the camera's screen and the monitor stream only (`none`, `uclog709`, `lib:<library id>`); null is
     * automatic (the UC-Log viewing LUT when recording UC-Log). Added in protocol v1 (D56).
     */
    @Serializable
    @SerialName("set_monitor_look")
    data class SetMonitorLook(val token: String?) : CommandBody

    /** AE and AWB locks (null leaves a lock as it is). Added in protocol v1 (D56). */
    @Serializable
    @SerialName("set_locks")
    data class SetLocks(val aeLock: Boolean? = null, val awbLock: Boolean? = null) : CommandBody

    /** Video or photo mode. Refused while recording. Added in protocol v1 (D56). */
    @Serializable
    @SerialName("set_capture_mode")
    data class SetCaptureMode(val mode: CaptureModeWire) : CommandBody

    /** Slave mode (REQ-NET-008): dimmed camera screen with minimal UI; recording is not affected. Added in v1 (D56). */
    @Serializable
    @SerialName("set_slave_mode")
    data class SetSlaveMode(val enabled: Boolean) : CommandBody

    /** Ask for a fresh [ControlMessage.Status]; acknowledged after it is sent. */
    @Serializable
    @SerialName("request_status")
    data object RequestStatus : CommandBody
}

/** Extra data for some acks. */
@Serializable
data class AckDetail(
    /** For a start: common time the take is (or was) scheduled to start; for a stop, when it stopped. */
    val atCommonNanos: Long? = null,
    /** How late the camera began a scheduled start (it starts late rather than not at all), in microseconds. */
    val lateMicros: Long? = null
)

@Serializable
enum class ErrorCode {
    /** The message `type` is not known by this version. */
    UNKNOWN_MESSAGE,

    /** The command is known but this camera does not support it (hardware or feature off). */
    UNSUPPORTED,

    /** A value is out of range (e.g. ISO, a start time too far ahead). */
    INVALID_ARGUMENT,

    /** Another take is recording. */
    ALREADY_RECORDING,

    /** A stop named a take that is not recording. */
    NOT_RECORDING,

    /** Not allowed now (e.g. changing profile while recording). */
    BUSY,

    /** A scheduled start needs the common clock, which is not synced yet. */
    CLOCK_NOT_SYNCED,

    /** Not enough free space to record. */
    STORAGE_FULL,

    /** The camera or encoder failed. */
    CAMERA_ERROR,

    /** The controller gave up waiting (link down longer than the command lifetime). Local only, never on the wire. */
    TIMED_OUT
}

/** Capture mode of the camera screen. */
@Serializable
enum class CaptureModeWire { VIDEO, PHOTO }

@Serializable
enum class ByeReason { SHUTDOWN, UNPAIRED, REPLACED, PROTOCOL_ERROR }

@Serializable
enum class RecordingState { IDLE, SCHEDULED, RECORDING, ERROR }

/** Android PowerManager thermal status, by name (THERMAL_STATUS_NONE … SHUTDOWN). */
@Serializable
enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/** Warnings the camera raises and the controllers show (REQ-NET-008). Each has a user-visible string. */
@Serializable
enum class StatusWarning { BATTERY_LOW, THERMAL, STORAGE_LOW, CLOCK_NOT_SYNCED, RECORDING_ERROR }

/** What a predictive health alert is about (D52). */
@Serializable
enum class HealthAlertKind { BATTERY, STORAGE, THERMAL }

/** A predicted problem on the camera: [kind] runs out (or overheats) in about [minutes] (0: now). */
@Serializable
data class HealthAlertInfo(val kind: HealthAlertKind, val critical: Boolean = false, val minutes: Int = 0)

/**
 * Detailed health of the camera phone (D52), next to the summary fields of [CameraStatus], so a monitor can show the
 * same indicators and banner as the camera screen. Added in protocol version 1 as an optional field: older peers ignore
 * it (unknown keys are tolerated), and a camera that does not measure something leaves it null.
 */
@Serializable
data class HealthDetail(
    /** Battery minutes left at the drain measured this session. */
    val batteryMinutes: Int? = null,
    /** Battery temperature in tenths of °C. */
    val batteryTempTenthsC: Int? = null,
    /** `PowerManager.getThermalHeadroom(0)` in hundredths (100 = SEVERE). */
    val thermalHeadroomPercent: Int? = null,
    /** Recording minutes left at the current mode on the effective location. */
    val storageMinutes: Int? = null,
    /** Free space on that location in megabytes. */
    val freeMegabytes: Long? = null,
    val alerts: List<HealthAlertInfo> = emptyList()
)

/** Current capture settings, for the monitor's display. Nulls are "unknown / not reported". */
@Serializable
data class CaptureSettings(
    val autoExposure: Boolean = true,
    val iso: Int? = null,
    val exposureTimeNanos: Long? = null,
    val autoFocus: Boolean = true,
    val focusDiopters: Float? = null,
    val autoWhiteBalance: Boolean = true,
    val whiteBalanceKelvin: Int? = null,
    /** Exposure compensation in EV (auto exposure). The fields below were added in protocol v1 (D56). */
    val evCompensation: Float? = null,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false
)

/**
 * Everything a controller needs to show a camera. [bootId] changes whenever the camera app restarts; [version] grows
 * on every change within one boot, so a controller keeps the newest status even if messages arrive late or twice.
 */
@Serializable
data class CameraStatus(
    val bootId: String,
    val version: Long,
    val recording: RecordingState = RecordingState.IDLE,
    val recordingId: String? = null,
    val recordingStartCommonNanos: Long? = null,
    val scheduledStartCommonNanos: Long? = null,
    val clockSynced: Boolean = false,
    val clockErrorMicros: Long? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val thermal: ThermalLevel = ThermalLevel.NONE,
    val freeSpaceMinutes: Int? = null,
    val profileId: String? = null,
    val lutId: String? = null,
    val audioSource: String? = null,
    val capture: CaptureSettings = CaptureSettings(),
    val warnings: Set<StatusWarning> = emptySet(),
    val health: HealthDetail? = null,
    // Added in protocol v1 as optional fields (D56): older peers ignore them.
    /** Look on the camera's screen and the monitor stream ([CommandBody.SetMonitorLook]); [lutId] is the recorded one. */
    val monitorLook: String? = null,
    val captureMode: CaptureModeWire? = null,
    val slaveMode: Boolean = false,
    /** Length of the take so far (refreshed at most twice a second). */
    val elapsedMs: Long? = null,
    /** Time-of-day timecode of the take's start on the common clock, when synced. */
    val timecode: String? = null,
    /** Frames the encoder dropped in the current (or last) take (D57); null when not reported. */
    val droppedFrames: Long? = null,
    /** Recording profiles a controller may pick with [CommandBody.SelectProfile] (D57); empty when not reported. */
    val profiles: List<ChoiceInfo> = emptyList(),
    /** LUTs a controller may pick with [CommandBody.SelectLut] (D57); empty when not reported. */
    val luts: List<ChoiceInfo> = emptyList()
) {
    /** True when [other] is a newer state of the same or a newer camera boot (a different boot always wins). */
    fun isSupersededBy(other: CameraStatus): Boolean = other.bootId != bootId || other.version > version
}

/**
 * One entry of a camera's pickable list (a recording profile or a LUT), with the camera's own label so the controller
 * shows the same text. [codec] and [fps] are set for profiles (e.g. "HEVC", 25) so a monitor can group profiles by
 * codec and run its timecode at the right frame rate. Optional fields of protocol v1 (D57).
 */
@Serializable
data class ChoiceInfo(val id: String, val label: String, val codec: String? = null, val fps: Int? = null)

/** A finished take on the camera (REQ-NET-006: listed for upload to the master). */
@Serializable
data class ClipInfo(
    val recordingId: String,
    val fileName: String,
    val startCommonNanos: Long?,
    val durationMs: Long,
    val bytes: Long,
    val timecode: String? = null
)

/** Result of decoding one frame: a known message, or a message type this version does not know. */
sealed interface Decoded {
    data class Message(val message: ControlMessage) : Decoded

    data class Unknown(val type: String) : Decoded
}

object ControlProtocol {
    /** Version of the control protocol, negotiated in the handshake and advertised in DNS-SD TXT. */
    const val VERSION = 1

    /** Oldest version this build still speaks. */
    const val MIN_VERSION = 1

    /** TCP port of the control channel (47800 is the UDP latency probe, 47801 the WebRTC signalling, 47803 the clock). */
    const val DEFAULT_PORT = 47_802

    const val HEARTBEAT_INTERVAL_MS = 1_000L

    /** No frame for this long and the link is considered down (it is then closed and redialled). */
    const val LINK_TIMEOUT_MS = 4_000L

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val knownTypes: Set<String> = ControlMessage.serializer().descriptor.let { d ->
        // The sealed descriptor's second element lists the subclasses by serial name.
        val subclasses = d.getElementDescriptor(1)
        (0 until subclasses.elementsCount).map { subclasses.getElementName(it) }.toSet()
    }

    fun encode(message: ControlMessage): ByteArray = json.encodeToString(ControlMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    /** Throws [IllegalArgumentException] for malformed JSON or a known type with bad fields. */
    fun decode(bytes: ByteArray): Decoded {
        val text = bytes.toString(Charsets.UTF_8)
        return try {
            val obj: JsonObject = json.parseToJsonElement(text).jsonObject
            val type = requireNotNull(obj["type"]?.jsonPrimitive?.content) { "no type" }
            if (type !in knownTypes) {
                Decoded.Unknown(type)
            } else {
                Decoded.Message(json.decodeFromJsonElement(ControlMessage.serializer(), obj))
            }
        } catch (e: SerializationException) {
            throw IllegalArgumentException("bad control message: ${e.message}", e)
        } catch (e: IllegalStateException) {
            throw IllegalArgumentException("bad control message: ${e.message}", e)
        }
    }
}
