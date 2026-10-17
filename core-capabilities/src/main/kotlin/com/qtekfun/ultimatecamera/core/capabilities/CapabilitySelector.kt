package com.qtekfun.ultimatecamera.core.capabilities

/**
 * Picks recording configurations from probed results only (never from what is merely advertised), and gives the
 * guided-degradation ladder of REQ-NFR-001: when the phone heats up while recording, step down to the next held
 * configuration that loads the pipeline less.
 */
class CapabilitySelector(private val capabilities: DeviceCapabilities, private val ladder: List<LadderRung> = BaseTargetLadder.rungs) {
    /** True if [config] was measured to hold on [cameraId], or a more demanding configuration was. */
    fun held(cameraId: String, config: VideoConfig): Boolean = capabilities.camera(cameraId)?.results.orEmpty().any {
        it.outcome == ProbeOutcome.ACCEPTED_AND_HELD && !it.thermalLimited && it.config.covers(config)
    }

    /** The held ladder rungs of [cameraId], best first. [lut] NONE gives the flat-recording (monitor-only LUT) ladder. */
    fun heldLadder(cameraId: String, lut: LutMode = LutMode.HARDWARE): List<VideoConfig> {
        val results = capabilities.camera(cameraId)?.results.orEmpty()
        return ladder.mapNotNull { rung ->
            results.map { it.config }
                .filter { c -> c.vendorMode == null && c.width == rung.width && c.height == rung.height && c.fps == rung.fps && c.tenBit == rung.tenBit }
                .map { it.copy(lut = lut) }
                .firstOrNull { held(cameraId, it) }
        }.distinct()
    }

    /**
     * The configurations whose vendor [mode] variant was measured to hold on [cameraId] (thermal doubt excluded), best
     * first. Empty when the variant was never probed, refused or dropped: such a mode is never offered (D37, D63).
     */
    fun heldVariants(cameraId: String, mode: String): List<VideoConfig> = capabilities.camera(cameraId)?.results.orEmpty()
        .filter { it.config.vendorMode == mode && it.outcome.held && !it.thermalLimited }
        .map { it.config }
        .distinct()
        .sortedByDescending { it.cost }

    /** The best configuration that held on [cameraId], or null if nothing was probed or nothing held. */
    fun best(cameraId: String, lut: LutMode = LutMode.HARDWARE): VideoConfig? = heldLadder(cameraId, lut).firstOrNull()

    /** Guided degradation: the configurations to fall back to from [current], each one lighter than the one before. */
    fun degradationLadder(cameraId: String, current: VideoConfig): List<VideoConfig> = buildList {
        var at = current
        while (true) {
            val next = nextLower(cameraId, at) ?: break
            add(next)
            at = next
        }
    }

    /** The best held configuration that loads the pipeline less than [current] (4K30 10-bit → 1080p60, not → 4K60 8-bit). */
    fun nextLower(cameraId: String, current: VideoConfig): VideoConfig? =
        heldLadder(cameraId, current.lut).firstOrNull { it.cost < current.cost && it != current }
}
