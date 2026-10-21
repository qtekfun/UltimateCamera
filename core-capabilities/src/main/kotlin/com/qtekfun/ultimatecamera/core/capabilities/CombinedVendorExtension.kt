package com.qtekfun.ultimatecamera.core.capabilities

/**
 * Several [VendorExtension]s run as one (D104), so a run can probe e.g. [SensorHdrExtension] and [PacingVariants]
 * together. A variant ([VideoConfig.vendorMode]) belongs to the first part whose [VendorExtension.variants] lists it on the
 * camera, and every call for it (parameters, operating mode, use case, the id stored with the result) goes to that part
 * alone. A plain configuration goes to the first part, so the plain session gets exactly what that part gives it.
 */
class CombinedVendorExtension(private val parts: List<VendorExtension>) : VendorExtension {
    constructor(vararg parts: VendorExtension) : this(parts.toList())

    init {
        require(parts.isNotEmpty()) { "no extension to combine" }
    }

    override val id: String = parts.joinToString("+") { it.id }

    override fun appliesTo(identity: DeviceIdentity, camera: AdvertisedCamera): Boolean = parts.any { it.appliesTo(identity, camera) }

    override fun sessionParameters(camera: AdvertisedCamera, config: VideoConfig): List<VendorParameter> =
        owner(camera, config)?.sessionParameters(camera, config).orEmpty()

    override fun sessionType(camera: AdvertisedCamera, config: VideoConfig): Int? = owner(camera, config)?.sessionType(camera, config)

    override fun variants(camera: AdvertisedCamera): List<String> = parts.flatMap { it.variants(camera) }.distinct()

    override fun plans(camera: AdvertisedCamera, config: VideoConfig): Boolean = owner(camera, config)?.plans(camera, config) ?: false

    override fun recordUseCase(camera: AdvertisedCamera, config: VideoConfig): Boolean = owner(camera, config)?.recordUseCase(camera, config) ?: false

    override fun idFor(camera: AdvertisedCamera, config: VideoConfig): String = owner(camera, config)?.idFor(camera, config) ?: id

    /** The part that speaks for [config] on [camera]: the variant's owner, the first part for a plain one, null for a variant nobody offers. */
    private fun owner(camera: AdvertisedCamera, config: VideoConfig): VendorExtension? {
        val mode = config.vendorMode ?: return parts.first()
        return parts.firstOrNull { mode in it.variants(camera) }
    }
}
