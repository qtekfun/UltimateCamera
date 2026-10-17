package com.qtekfun.ultimatecamera.core.capabilities

import kotlinx.serialization.Serializable

/**
 * Which phone and which system build the catalogue was probed on. A different [fingerprint] or [sdk] (an OS update can
 * change the camera HAL and the codecs) makes the stored results stale.
 */
@Serializable
data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val sdk: Int,
    /** `Build.ID`, the only build field the catalogue seeds carry. */
    val buildId: String = "",
    /** `Build.FINGERPRINT`; empty when unknown (seeds). */
    val fingerprint: String = ""
)

/** A size the camera lists as a GPU (PRIVATE) output, with the fastest rate its minimum frame duration allows. */
@Serializable
data class AdvertisedMode(val width: Int, val height: Int, val maxFps: Int) {
    override fun toString(): String = "${width}x$height@$maxFps"
}

/** What Camera2 says about one camera before anything is opened (StreamConfigurationMap and friends). */
@Serializable
data class AdvertisedCamera(
    val id: String,
    val facing: String,
    val physicalIds: List<String> = emptyList(),
    val videoModes: List<AdvertisedMode>,
    /** 10-bit profiles the camera lists (vendor profiles such as Dolby Vision are left out). */
    val tenBitProfiles: List<DynamicRange> = emptyList(),
    val recommendedTenBitProfile: DynamicRange? = null,
    /** Vendor capture-request keys (e.g. `com.oplus.…`, `org.codeaurora.qcamera3.…`) for [VendorExtension]s to look at. */
    val vendorRequestKeys: List<String> = emptyList(),
    /** Vendor characteristic keys (read-only facts such as QTI `…available_video_hdr_modes`). */
    val vendorCharacteristicKeys: List<String> = emptyList(),
    /** Vendor keys among `getAvailableSessionKeys()` (the only ones a HAL honours as session parameters). */
    val vendorSessionKeys: List<String> = emptyList(),
    /** Values of the vendor HDR-looking characteristics, read defensively as text ("1,2"); unreadable ones are left out. */
    val vendorHdrValues: Map<String, String> = emptyMap(),
    /** Sensor HDR video mode the [SensorHdrExtension] found, as text for the catalogue and reports; null when none. */
    val sensorHdr: String? = null,
    /** HDR photo methods the camera offers (D64): `extension_hdr`, `extension_auto`, `extension_night`, `jpeg_r`, `bracket`… */
    val photoHdr: List<String> = emptyList(),
    /** Portrait depth sources (D73): `depth16`, `depth_point_cloud`, `extension_bokeh`, `stereo:<main>+<second>`. */
    val depthSources: List<String> = emptyList()
) {
    fun mode(width: Int, height: Int): AdvertisedMode? = videoModes.firstOrNull { it.width == width && it.height == height }

    /** The 10-bit profile a 10-bit probe uses: the recommended one if usable, else HLG10 > HDR10 > HDR10+. */
    val tenBitProfile: DynamicRange?
        get() = recommendedTenBitProfile?.takeIf { it in tenBitProfiles }
            ?: DynamicRange.TEN_BIT_PREFERENCE.firstOrNull { it in tenBitProfiles }
}

/** A hardware video encoder as MediaCodecInfo describes it. */
@Serializable
data class AdvertisedEncoder(
    val name: String,
    val codec: CodecType,
    val tenBit: Boolean,
    /** `"3840x2160@60" -> true/false` from `VideoCapabilities.areSizeAndRateSupported`; missing keys are unknown. */
    val supports: Map<String, Boolean> = emptyMap()
) {
    fun supports(config: VideoConfig): Boolean? = supports["${config.width}x${config.height}@${config.fps}"]
}

/** Input of the probe planner: the device and everything it advertises. [encoders] null means "not listed". */
@Serializable
data class AdvertisedDevice(val identity: DeviceIdentity, val cameras: List<AdvertisedCamera>, val encoders: List<AdvertisedEncoder>? = null) {
    fun camera(id: String): AdvertisedCamera? = cameras.firstOrNull { it.id == id }
}

/** How one probe ended. */
@Serializable
enum class ProbeOutcome(
    /** A device verdict worth keeping across runs (the others are re-planned on the next run). */
    val definitive: Boolean
) {
    /** The session was created and held the frame rate with zero dropped frames. */
    ACCEPTED_AND_HELD(true),

    /** Frames were dropped (sensor, GPU or encoder). */
    DROPS(true),

    /** No drops counted, but the measured rate stayed under the target. */
    LOW_FPS(true),

    /**
     * The camera itself delivered fewer frames than asked (the vivo X500 Pro Max ran "1080p60" at 30 fps under a
     * variable AE range), so the pipeline was never loaded at the target: not a load verdict, implies nothing about
     * heavier configurations, and is retried on the next run.
     */
    CAMERA_RATE_LIMITED(false),

    /** The camera HAL refused the session (e.g. 10-bit HLG above 30 fps on the Pixel 8). */
    SESSION_REJECTED(true),

    /** The camera failed to open, disconnected or errored: may be another app; not a capability verdict. */
    CAMERA_ERROR(false),

    /**
     * The app died while this configuration was running (found as the in-progress marker on the next start). Never
     * retried automatically: a HAL crash can wedge the camera until a reboot.
     */
    CRASHED(true),

    /** The engine's watchdog stopped the probe: the camera or the codec stopped answering. Not retried automatically. */
    TIMEOUT(true),

    /** No hardware encoder for this codec / bit depth, or the encoder refused the format. */
    ENCODER_UNSUPPORTED(true),

    /** Not tried: the camera does not list the output size or any 10-bit profile, so the session cannot be built. */
    NOT_ADVERTISED_SKIPPED(true),

    /** Not tried: a cheaper configuration already failed in a way that implies this one fails too. */
    IMPLIED_FAILURE(true),

    /** Not tried: a more demanding configuration already held. */
    IMPLIED_HELD(true),

    /** Not tried: the phone was too warm, or a probe heated it too much. */
    SKIPPED_THERMAL(false),

    /** Not tried: the run's time budget ran out. */
    SKIPPED_BUDGET(false);

    val held: Boolean get() = this == ACCEPTED_AND_HELD || this == IMPLIED_HELD
}

/** One configuration's result. [advertised] (what Camera2 and MediaCodec claim) is kept apart from what was measured. */
@Serializable
data class ProbeResult(
    val cameraId: String,
    val config: VideoConfig,
    /** The camera lists the size at this rate and the profile, and the encoder does not deny the size and rate. */
    val advertised: Boolean,
    val outcome: ProbeOutcome,
    val measuredFps: Double? = null,
    val droppedFrames: Int? = null,
    /** Highest `PowerManager` thermal status seen during the probe. */
    val thermalStatusMax: Int? = null,
    /** Dropped while the phone was warm: says nothing about the configuration, so it implies nothing. */
    val thermalLimited: Boolean = false,
    /** Label of the configuration whose result decided an IMPLIED_* outcome. */
    val impliedBy: String? = null,
    /** [VendorExtension.id] whose session parameters were set, if any. */
    val vendorExtension: String? = null,
    val reason: String? = null,
    val probedAtEpochMs: Long = 0
) {
    /** Kept across runs; anything else is planned again. */
    val isDefinitive: Boolean get() = outcome.definitive && !thermalLimited
}

/** Written to disk before a probe opens the camera and cleared after it: if it survives, that probe crashed the app. */
@Serializable
data class InProgressProbe(val cameraId: String, val config: VideoConfig)

@Serializable
data class CameraCapabilities(val advertised: AdvertisedCamera, val results: List<ProbeResult> = emptyList()) {
    fun result(config: VideoConfig): ProbeResult? = results.lastOrNull { it.config == config }
}

/** Why stored capabilities cannot be used as they are. */
enum class ReprobeReason { DEVICE_CHANGED, OS_CHANGED, ENGINE_CHANGED, INCOMPLETE }

/**
 * The device capability catalogue (docs/01-arquitectura.md): filled by the probe engine at first start, cached in the
 * app's no-backup directory, regenerable, never in the backup.
 */
@Serializable
data class DeviceCapabilities(
    val schemaVersion: Int = SCHEMA_VERSION,
    val identity: DeviceIdentity,
    /** [ProbeEngine.ENGINE_VERSION] that produced the results; a new planner or criterion re-probes. */
    val engineVersion: Int,
    val cameras: List<CameraCapabilities>,
    val encoders: List<AdvertisedEncoder>? = null,
    /** Every planned probe got a definitive outcome (no cancellation, thermal stop or budget cut). */
    val complete: Boolean = false,
    val inProgress: InProgressProbe? = null,
    val updatedAtEpochMs: Long = 0
) {
    fun camera(id: String): CameraCapabilities? = cameras.firstOrNull { it.advertised.id == id }

    /** Null when these results can be used as they are on [current] with [engineVersion]. */
    fun reprobeReason(current: DeviceIdentity, engineVersion: Int = ProbeEngine.ENGINE_VERSION): ReprobeReason? = when {
        identity.manufacturer != current.manufacturer || identity.model != current.model || identity.device != current.device ->
            ReprobeReason.DEVICE_CHANGED
        identity.fingerprint != current.fingerprint || identity.sdk != current.sdk || identity.buildId != current.buildId ->
            ReprobeReason.OS_CHANGED
        this.engineVersion != engineVersion -> ReprobeReason.ENGINE_CHANGED
        !complete -> ReprobeReason.INCOMPLETE
        else -> null
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
