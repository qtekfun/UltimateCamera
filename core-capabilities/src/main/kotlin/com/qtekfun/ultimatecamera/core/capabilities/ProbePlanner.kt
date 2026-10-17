package com.qtekfun.ultimatecamera.core.capabilities

/**
 * One entry of the plan. [preset] is an outcome known without opening the camera (the size is not an output at all,
 * no 10-bit profile, no encoder); such entries are recorded, never run.
 */
data class PlannedProbe(val cameraId: String, val config: VideoConfig, val advertised: Boolean, val preset: ProbeOutcome? = null, val reason: String? = null)

/**
 * Builds the ordered probe list: the base-target ladder resolved per camera (its 10-bit profile, the hardware encoders),
 * cheapest first, so that the engine can stop early: a failure on load implies every more demanding configuration
 * fails, a success implies every lighter one holds (see [Implications]). Rates above the advertised maximum of a listed
 * size are planned too (`advertised = false`): the stream map is not trusted.
 */
class ProbePlanner(private val policy: ProbePolicy = ProbePolicy()) {
    /**
     * [variants] gives the vendor sensor modes to try on a camera ([VendorExtension.variants]); each is planned after the
     * plain ladder as a variant of the 4K 10-bit rungs that can be built at all, cheapest first, so a refusal at 4K30
     * skips 4K60 ([Implications]). Plain results never stand for a variant.
     */
    fun plan(
        device: AdvertisedDevice,
        cameraIds: List<String> = primaryCameraIds(device),
        variants: (AdvertisedCamera) -> List<String> = { emptyList() }
    ): List<PlannedProbe> = cameraIds.flatMap { id ->
        val camera = device.camera(id) ?: return@flatMap emptyList()
        val withLut = policy.ladder.distinct().map { resolve(camera, device.encoders, it) }
            .distinctBy { it.config }
            .sortedBy { it.config.cost }
        val plain = withLut.flatMap { probe ->
            val c = probe.config
            if (c.lut != LutMode.NONE && c.pixels >= policy.noLutFallbackMinPixels) {
                listOf(probe, probe.copy(config = c.copy(lut = LutMode.NONE)))
            } else {
                listOf(probe)
            }
        }.distinctBy { it.config }
        val variantBase = withLut.filter { it.preset == null && it.config.tenBit && it.config.pixels >= policy.noLutFallbackMinPixels }
        plain + variants(camera).distinct().flatMap { mode -> variantBase.map { it.copy(config = it.config.copy(vendorMode = mode)) } }
    }

    private fun resolve(camera: AdvertisedCamera, encoders: List<AdvertisedEncoder>?, rung: LadderRung): PlannedProbe {
        val profile = if (rung.tenBit) camera.tenBitProfile else DynamicRange.STANDARD
        val codec = codecFor(encoders, rung.tenBit)
        val config = VideoConfig(rung.width, rung.height, rung.fps, codec ?: CodecType.HEVC, profile ?: DynamicRange.HLG10, rung.lut)
        val mode = camera.mode(rung.width, rung.height)
        val fpsAdvertised = mode != null && mode.maxFps >= rung.fps
        val encoderAdvertised = encoders?.filter { it.codec == config.codec }?.none { it.supports(config) == false } ?: true
        val advertised = fpsAdvertised && profile != null && codec != null && encoderAdvertised
        val skip: Pair<ProbeOutcome, String>? = when {
            mode == null -> ProbeOutcome.NOT_ADVERTISED_SKIPPED to "camera ${camera.id} lists no ${rung.width}x${rung.height} output"
            profile == null -> ProbeOutcome.NOT_ADVERTISED_SKIPPED to "camera ${camera.id} lists no 10-bit profile"
            !fpsAdvertised && !policy.tryUnadvertisedFps -> ProbeOutcome.NOT_ADVERTISED_SKIPPED to "advertised up to ${mode.maxFps} fps"
            codec == null -> ProbeOutcome.ENCODER_UNSUPPORTED to "no hardware ${if (rung.tenBit) "10-bit " else ""}encoder"
            else -> null
        }
        return PlannedProbe(camera.id, config, advertised, skip?.first, skip?.second)
    }

    /** HEVC first (10-bit Main10, the project's main codec), then AV1 for 10-bit or H.264 for 8-bit. Null: none. */
    private fun codecFor(encoders: List<AdvertisedEncoder>?, tenBit: Boolean): CodecType? {
        if (encoders == null) return CodecType.HEVC
        val order = if (tenBit) listOf(CodecType.HEVC, CodecType.AV1) else listOf(CodecType.HEVC, CodecType.H264)
        return order.firstOrNull { codec -> encoders.any { it.codec == codec && (it.tenBit || !tenBit) } }
    }

    companion object {
        /** The default camera to auto-configure: the first back camera (the logical main camera on every phone seen). */
        fun primaryCameraIds(device: AdvertisedDevice): List<String> =
            listOfNotNull((device.cameras.firstOrNull { it.facing == "BACK" } ?: device.cameras.firstOrNull())?.id)
    }
}

/** What a configuration's result says about the others (same camera). Only verdicts with no thermal doubt count. */
internal object Implications {
    fun decide(config: VideoConfig, known: List<ProbeResult>): Pair<ProbeOutcome, ProbeResult>? {
        // A vendor variant and the plain session are different sessions: neither says anything about the other.
        val usable = known.filter { it.isDefinitive && it.impliedBy == null && it.config.vendorMode == config.vendorMode }
        usable.firstOrNull { it.outcome == ProbeOutcome.ACCEPTED_AND_HELD && it.config.covers(config) }
            ?.let { return ProbeOutcome.IMPLIED_HELD to it }
        return usable.firstOrNull { impliesFailure(it, config) }?.let { ProbeOutcome.IMPLIED_FAILURE to it }
    }

    private fun impliesFailure(r: ProbeResult, c: VideoConfig): Boolean {
        val f = r.config
        return when (r.outcome) {
            // Failed on load: anything at least as large and as fast, with at least the same total load, fails too
            // (4K30 10-bit dropping skips 4K60 8-bit; its no-LUT variant, lighter, is still tried).
            ProbeOutcome.DROPS, ProbeOutcome.LOW_FPS -> c.codec == f.codec && c.pixels >= f.pixels && c.fps >= f.fps && c.cost >= f.cost
            // The HAL refuses this size and profile at this rate: it refuses it faster too, whatever the LUT or codec.
            ProbeOutcome.SESSION_REJECTED -> c.width == f.width && c.height == f.height && c.dynamicRange == f.dynamicRange && c.fps >= f.fps
            // The camera died or hung: nothing at least as heavy is tried on this camera, whatever the codec.
            ProbeOutcome.CRASHED, ProbeOutcome.TIMEOUT -> c.pixels >= f.pixels && c.fps >= f.fps && (c.tenBit || !f.tenBit)
            ProbeOutcome.ENCODER_UNSUPPORTED -> c.codec == f.codec && c.pixels >= f.pixels && c.fps >= f.fps && (c.tenBit || !f.tenBit)
            else -> false
        }
    }
}
