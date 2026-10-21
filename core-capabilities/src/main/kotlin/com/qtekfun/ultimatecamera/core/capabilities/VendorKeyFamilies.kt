package com.qtekfun.ultimatecamera.core.capabilities

/**
 * The vendor keys of one namespace on a camera (e.g. `org.codeaurora`): how many distinct keys it lists and how many of
 * them are session keys. Whether the session list says anything depends on the HAL: some list every vendor key as a
 * session key (D102), others only a real subset (D105), so the screen shows the session count only when it differs.
 */
data class VendorKeyFamily(val namespace: String, val keys: Int, val sessionKeys: Int = keys) {
    /** The session list is a real subset of the keys, not the whole list repeated. */
    val sessionSubset: Boolean get() = sessionKeys < keys
}

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
        val session = camera.vendorSessionKeys.toSet()
        return all.groupBy(::namespace)
            .map { (ns, keys) -> VendorKeyFamily(ns, keys.size, keys.count { it in session }) }
            .sortedWith(compareByDescending<VendorKeyFamily> { it.keys }.thenBy { it.namespace })
    }

    /** `com.vendor.camera.feature` → `com.vendor`; `vendor.camera.feature` → `vendor`. */
    fun namespace(key: String): String {
        val parts = key.split('.')
        return if (parts.size >= 2 && parts[0] in DOMAINS) "${parts[0]}.${parts[1]}" else parts[0]
    }
}
