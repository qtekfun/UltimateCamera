package com.qtekfun.ultimatecamera.core.capabilities

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A catalogue seed (`catalog/devices/<brand>-<model>.json`, written by `scripts/spike/report.py catalog`, schema 1):
 * what one phone advertised during the spike plus what its spike runs measured. The app does not ship seeds yet; they
 * are fixtures and, later, prior knowledge for the planner.
 */
data class CatalogSeed(
    val device: AdvertisedDevice,
    /** Labels ([VideoConfig.label]) that met target in the spike. */
    val heldLabels: Set<String>,
    /** Labels the camera HAL refused. */
    val rejectedLabels: Set<String>
) {
    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val MODE = Regex("""(\d+)x(\d+)@(\d+)""")

        fun parse(text: String): CatalogSeed {
            val seed = JSON.decodeFromString(SeedFile.serializer(), text)
            require(seed.schema == 1) { "unsupported seed schema ${seed.schema}" }
            val d = seed.device
            val identity = DeviceIdentity(d.manufacturer, d.brand, d.model, d.device, d.sdk, buildId = d.build.orEmpty())
            val cameras = seed.cameras.orEmpty().map { c ->
                AdvertisedCamera(
                    id = c.id,
                    facing = c.facing,
                    physicalIds = c.physicalIds,
                    videoModes = c.videoModes.mapNotNull { m ->
                        MODE.matchEntire(m)?.destructured?.let { (w, h, f) -> AdvertisedMode(w.toInt(), h.toInt(), f.toInt()) }
                    },
                    tenBitProfiles = c.tenBitProfiles.mapNotNull(DynamicRange::parse),
                    recommendedTenBitProfile = c.recommendedTenBitProfile?.let(DynamicRange::parse)
                )
            }
            val encoders = seed.encoders?.mapNotNull { e ->
                CodecType.entries.firstOrNull { it.name == e.codec }?.let { AdvertisedEncoder(e.name, it, e.tenBit, e.supports) }
            }
            return CatalogSeed(
                AdvertisedDevice(identity, cameras, encoders),
                heldLabels = seed.tiers.orEmpty().map { it.label }.toSet(),
                rejectedLabels = seed.rejectedConfigs.toSet()
            )
        }
    }

    @Serializable
    private data class SeedFile(
        val schema: Int,
        val device: SeedDevice,
        val cameras: List<SeedCamera>? = null,
        val encoders: List<SeedEncoder>? = null,
        val rejectedConfigs: List<String> = emptyList(),
        val tiers: List<SeedTier>? = null
    )

    @Serializable
    private data class SeedDevice(val manufacturer: String, val brand: String, val model: String, val device: String, val sdk: Int, val build: String? = null)

    @Serializable
    private data class SeedCamera(
        val id: String,
        val facing: String,
        val physicalIds: List<String> = emptyList(),
        val videoModes: List<String> = emptyList(),
        val tenBitProfiles: List<String> = emptyList(),
        val recommendedTenBitProfile: String? = null
    )

    @Serializable
    private data class SeedEncoder(val name: String, val codec: String, val tenBit: Boolean = false, val supports: Map<String, Boolean> = emptyMap())

    @Serializable
    private data class SeedTier(val label: String)
}
