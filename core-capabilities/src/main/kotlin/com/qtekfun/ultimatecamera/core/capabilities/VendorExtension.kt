package com.qtekfun.ultimatecamera.core.capabilities

import kotlinx.serialization.Serializable

/** Value type of a vendor capture-request key, as `CaptureRequest.Key(name, type)` needs it. */
@Serializable
enum class VendorValueType { BYTE, INT, LONG, FLOAT, INT_ARRAY }

/**
 * One vendor session parameter (e.g. a `com.oplus.…` or `org.codeaurora.qcamera3.…` key). [value] is the textual form
 * of the value ("1", "60", "1,2" for arrays); the runner converts it and sets it on the session-parameters request only
 * if the camera lists [key] among its available request keys.
 */
@Serializable
data class VendorParameter(val key: String, val type: VendorValueType, val value: String)

/**
 * Manufacturer extension point: extra session parameters / vendor tags that unlock modes the plain Camera2 session
 * does not get. Everything vendor-specific goes behind this interface (CLAUDE.md) and its use is recorded per result
 * ([ProbeResult.vendorExtension]). If a probe with vendor parameters is refused or errors, the engine retries it once
 * without them, so an extension can never lose a configuration the plain session holds.
 */
interface VendorExtension {
    /** Stable id stored with each result, e.g. "oplus-session-v1". */
    val id: String

    fun appliesTo(identity: DeviceIdentity, camera: AdvertisedCamera): Boolean

    fun sessionParameters(camera: AdvertisedCamera, config: VideoConfig): List<VendorParameter>

    /**
     * Vendor operating mode for `SessionConfiguration(sessionType, …)` (Qualcomm `0xF0xx`, Xiaomi `0x80xx`), or null for
     * a regular session. Only for a [VideoConfig.vendorMode] variant: a refused mode is a normal session refusal.
     */
    fun sessionType(camera: AdvertisedCamera, config: VideoConfig): Int? = null

    /**
     * Vendor sensor modes worth probing on [camera] as variants of the plain configurations ([VideoConfig.vendorMode]).
     * A variant runs only with its vendor parameters (never retried plain), so a held variant really held with them.
     */
    fun variants(camera: AdvertisedCamera): List<String> = emptyList()
}

/** The default: no vendor parameters on any phone. */
object NoVendorExtension : VendorExtension {
    override val id = "none"

    override fun appliesTo(identity: DeviceIdentity, camera: AdvertisedCamera) = false

    override fun sessionParameters(camera: AdvertisedCamera, config: VideoConfig) = emptyList<VendorParameter>()
}
