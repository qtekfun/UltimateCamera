package com.qtekfun.ultimatecamera.core.capabilities

/**
 * A sensor HDR video mode found on a camera: the vendor session parameters and/or the vendor operating mode that ask
 * the HAL for it, and [source], the key or vendor it was found from (shown in reports).
 */
data class SensorHdrMode(val source: String, val parameters: List<VendorParameter> = emptyList(), val sessionType: Int? = null) {
    /** One line for the catalogue and the diagnostics export. */
    val description: String
        get() = buildString {
            append(source)
            parameters.forEach { append(" ${it.key}=${it.value}") }
            sessionType?.let { append(" sessionType=0x${Integer.toHexString(it).uppercase()}") }
        }
}

/**
 * Experimental sensor HDR video (D37, D63): the staggered / zig-zag HDR modes phone makers use in their own camera apps,
 * reached only through what the camera itself advertises (docs/research/open-camera-fv5.md §5.2): vendor request keys
 * the camera lists, and vendor operating modes passed through the public `SessionConfiguration` constructor. Matching
 * is by key names, never by `Build.MANUFACTURER` (OPPO, OnePlus and realme share Oplus keys; Qualcomm keys appear on
 * many brands). Nothing is brute-forced: one value ("on") per known key.
 *
 * The mode is planned as a variant of the 4K 10-bit rungs ([ProbePlanner]) and offered only if such a variant held.
 * Whether the HAL really switched the sensor to HDR (rather than silently ignoring the key) cannot be measured by the
 * probe; the owner checks it on the phone (docs/TESTING.md).
 */
object SensorHdrExtension : VendorExtension {
    override val id = "sensor-hdr-v1"

    /** [VideoConfig.vendorMode] of the variant. */
    const val MODE = "sensor-hdr"

    /** Qualcomm (CAF) video HDR request key: 0 off, 1 on (Snapdragon Camera's `support_video_hdr_modes`). */
    const val QTI_VHDR_MODE = "org.codeaurora.qcamera3.video_hdr_mode.vhdr_mode"

    /** Qualcomm (QUIC) stream-config HDR video request key (byte, 1 = on). */
    const val QUIC_HDR_VIDEO_MODE = "org.quic.camera2.streamconfigs.HDRVideoMode"

    /** Qualcomm ZZHDR (zig-zag HDR) vendor operating mode. */
    const val QTI_ZZHDR_SESSION = 0xF002

    /** Xiaomi HDR10 video operating mode (FreeDcam `OpCode`). */
    const val XIAOMI_HDR10_SESSION = 0x8024

    override fun appliesTo(identity: DeviceIdentity, camera: AdvertisedCamera): Boolean = detect(camera) != null

    override fun sessionParameters(camera: AdvertisedCamera, config: VideoConfig): List<VendorParameter> =
        if (config.vendorMode == MODE) detect(camera)?.parameters.orEmpty() else emptyList()

    override fun sessionType(camera: AdvertisedCamera, config: VideoConfig): Int? = if (config.vendorMode == MODE) detect(camera)?.sessionType else null

    override fun variants(camera: AdvertisedCamera): List<String> = if (detect(camera) != null) listOf(MODE) else emptyList()

    /** The sensor HDR mode of [camera], from the keys it advertises; null when it advertises none (pure). */
    fun detect(camera: AdvertisedCamera): SensorHdrMode? = detect(camera.vendorRequestKeys, camera.vendorCharacteristicKeys, camera.vendorHdrValues)

    /**
     * Detection from key names. Order: explicit request keys (most specific first), then the vendor operating modes,
     * which are only tried where the camera shows the matching vendor's video-HDR characteristics (Qualcomm) or vendor
     * keys at all (Xiaomi). A readable QTI mode list holding only 0 (off) says the sensor has no video HDR: nothing.
     */
    fun detect(requestKeys: Collection<String>, characteristicKeys: Collection<String>, hdrValues: Map<String, String> = emptyMap()): SensorHdrMode? {
        val qtiModes = characteristicKeys.filter(::isQtiVideoHdrList).sorted()
        val qtiDenies = qtiModes.isNotEmpty() && qtiModes.all { key -> hdrValues[key]?.let(::onlyOff) == true }
        return when {
            qtiDenies -> null
            QTI_VHDR_MODE in requestKeys -> SensorHdrMode(QTI_VHDR_MODE, listOf(VendorParameter(QTI_VHDR_MODE, VendorValueType.INT, "1")))
            QUIC_HDR_VIDEO_MODE in requestKeys -> SensorHdrMode(QUIC_HDR_VIDEO_MODE, listOf(VendorParameter(QUIC_HDR_VIDEO_MODE, VendorValueType.BYTE, "1")))
            else -> oplus(requestKeys) ?: sessionMode(requestKeys + characteristicKeys, qtiModes)
        }
    }

    /** An Oplus (OPPO, OnePlus, realme) video HDR request key such as `com.oplus.video.hdr.mode`; set to 1. */
    private fun oplus(requestKeys: Collection<String>): SensorHdrMode? = requestKeys.sorted().firstOrNull { key ->
        val k = key.lowercase()
        (k.startsWith("com.oplus.") || k.startsWith("com.oppo.")) && "hdr" in k && "video" in k
    }?.let { SensorHdrMode(it, listOf(VendorParameter(it, VendorValueType.INT, "1"))) }

    private fun sessionMode(keys: Collection<String>, qtiModes: List<String>): SensorHdrMode? = when {
        qtiModes.isNotEmpty() -> SensorHdrMode(qtiModes.first(), sessionType = QTI_ZZHDR_SESSION)
        keys.any { it.startsWith("xiaomi.") || it.startsWith("com.xiaomi.") } -> SensorHdrMode("xiaomi", sessionType = XIAOMI_HDR10_SESSION)
        else -> null
    }

    /** QTI characteristics listing the sensor's video HDR modes (`…available_video_hdr_modes…`, `…vhdr_supported_modes`). */
    fun isQtiVideoHdrList(key: String): Boolean = (key.startsWith("org.codeaurora.") || key.startsWith("org.quic.") || key.startsWith("com.qti.")) &&
        ("available_video_hdr_modes" in key || "vhdr_supported_modes" in key)
}

/** A textual list of mode values ("0", "0,0") that holds nothing but off. */
private fun onlyOff(value: String): Boolean {
    val numbers = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return numbers.isNotEmpty() && numbers.all { it == "0" }
}
