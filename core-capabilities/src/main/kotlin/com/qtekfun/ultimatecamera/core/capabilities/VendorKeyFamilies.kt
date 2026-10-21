package com.qtekfun.ultimatecamera.core.capabilities

/**
 * The vendor keys of one namespace on a camera (e.g. `org.codeaurora`) and how many distinct keys it lists. There is no
 * session-key count: the platform lists every vendor key as a request key and as a session key alike (measured on three
 * phones from different makers, D102), so that count told nothing apart.
 */
data class VendorKeyFamily(val namespace: String, val keys: Int)

/**
 * Groups what a camera exposes beyond plain Camera2 by namespace (D101), so a run shows at a glance which manufacturer
 * keys a phone offers and a [VendorExtension] can be chosen by the keys it finds, never by brand or model. Keys a HAL
 * accepts without listing them do not show here: this is only what the camera admits to.
 */
object VendorKeyFamilies {
    /** Top-level domains whose keys are named `tld.organisation.…`; any other first segment is the namespace alone. */
    private val DOMAINS = setOf("com", "org", "net", "io", "cn", "vendor")

    /** Families on [camera], largest first. */
    fun of(camera: AdvertisedCamera): List<VendorKeyFamily> {
        val all = (camera.vendorRequestKeys + camera.vendorCharacteristicKeys + camera.vendorSessionKeys).toSet()
        return all.groupBy(::namespace)
            .map { (ns, keys) -> VendorKeyFamily(ns, keys.size) }
            .sortedWith(compareByDescending<VendorKeyFamily> { it.keys }.thenBy { it.namespace })
    }

    /** `com.vendor.camera.feature` → `com.vendor`; `vendor.camera.feature` → `vendor`. */
    fun namespace(key: String): String {
        val parts = key.split('.')
        return if (parts.size >= 2 && parts[0] in DOMAINS) "${parts[0]}.${parts[1]}" else parts[0]
    }
}
