package com.qtekfun.ultimatecamera.core.capabilities

import kotlin.math.floor

/**
 * One rung of the base-target ladder, before it is resolved to a camera's 10-bit profile and the encoders. A
 * [without10Bit] rung is planned only on a camera with no 10-bit profile, where the 10-bit rung of the size cannot run (D109).
 * [sourceWidth] x [sourceHeight] is the camera stream the GPU scales up to the rung's size ([VideoConfig.upscaled], D112).
 */
data class LadderRung(
    val width: Int,
    val height: Int,
    val fps: Int,
    val tenBit: Boolean,
    val lut: LutMode = LutMode.HARDWARE,
    val without10Bit: Boolean = false,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null
)

/**
 * The base-target ladder of REQ-NFR-001 and docs/spike-report.md, best first: 4K60 10-bit LUT → 4K60 8-bit →
 * 4K30 10-bit → 1080p60 → 1080p30 (all with the LUT burnt in). Smooth motion comes before bit depth (D105): a camera
 * that runs 4K60 only in 8-bit records 4K60 8-bit, not 4K30 10-bit. The selector walks it down; the planner probes it
 * bottom up.
 */
object BaseTargetLadder {
    val rungs = listOf(
        LadderRung(3840, 2160, 60, tenBit = true),
        LadderRung(3840, 2160, 60, tenBit = false),
        LadderRung(3840, 2160, 30, tenBit = true),
        // A camera without a 10-bit profile (often the selfie camera) still gets its 4K30 measured (D109).
        LadderRung(3840, 2160, 30, tenBit = false, without10Bit = true),
        LadderRung(1920, 1080, 60, tenBit = false),
        LadderRung(1920, 1080, 30, tenBit = false)
    )
}

/**
 * The ladder of one camera, best first (D112). A camera that lists no 4K output gets its largest real 16:9 size between
 * 1080p and 4K (a selfie sensor 3264 wide gives 3264x1836) and 4K upscaled from it, as the phone's own app records its
 * "4K" selfie. Both sit between the 4K and the 1080p rungs: the real size first, then the upscaled 4K.
 */
object CameraLadder {
    fun of(camera: AdvertisedCamera?, base: List<LadderRung> = BaseTargetLadder.rungs): List<LadderRung> {
        val sensor = camera?.let(::sensorSize) ?: return base
        val extra = rungsAt(sensor.width, sensor.height) + rungsAt(UHD_WIDTH, UHD_HEIGHT, sensor)
        val at = base.indexOfFirst { it.width.toLong() * it.height < sensor.width.toLong() * sensor.height }.let { if (it < 0) base.size else it }
        return base.take(at) + extra + base.drop(at)
    }

    /** The largest listed 16:9 size above 1080p and below 4K, on a camera that lists no 4K; null otherwise. */
    fun sensorSize(camera: AdvertisedCamera): AdvertisedMode? {
        if (camera.mode(UHD_WIDTH, UHD_HEIGHT) != null) return null
        return camera.videoModes
            .filter { it.width * WIDE_H == it.height * WIDE_W && it.height > FHD_HEIGHT && it.height < UHD_HEIGHT }
            .maxByOrNull { it.width.toLong() * it.height }
    }

    /** 60 before 30 and 10-bit before 8-bit (D105); the 8-bit 30 rung only where 10-bit cannot run, as in the base ladder. */
    private fun rungsAt(width: Int, height: Int, source: AdvertisedMode? = null): List<LadderRung> = listOf(
        LadderRung(width, height, FAST, tenBit = true),
        LadderRung(width, height, FAST, tenBit = false),
        LadderRung(width, height, SLOW, tenBit = true),
        LadderRung(width, height, SLOW, tenBit = false, without10Bit = true)
    ).map { it.copy(sourceWidth = source?.width, sourceHeight = source?.height) }

    private const val UHD_WIDTH = 3840
    private const val UHD_HEIGHT = 2160
    private const val FHD_HEIGHT = 1080
    private const val WIDE_W = 16
    private const val WIDE_H = 9
    private const val FAST = 60
    private const val SLOW = 30
}

/**
 * Limits of an auto-configuration run. The defaults keep the whole run around a minute on a capable phone and stop
 * sooner on a modest one (performance on modest phones is a design criterion, D14): short runs, a pause between them,
 * no probe started on a warm phone.
 */
data class ProbePolicy(
    val ladder: List<LadderRung> = BaseTargetLadder.rungs,
    val warmupMs: Long = 1_000,
    /** At 30 fps this is 120 frames: enough to see drops; the 5-minute sustained check is not part of auto-configuration. */
    val measureMs: Long = 4_000,
    /** Pause after every probe that opened the camera. */
    val coolDownMs: Long = 2_000,
    /** Whole run, including pauses and thermal waits. A probe is not started if it would end after the budget. */
    val budgetMs: Long = 180_000,
    /** A probe starts only at this thermal status or lower. */
    val maxStartThermalStatus: Int = ThermalStatus.LIGHT,
    /** Drops measured at this status or higher are "thermal-limited" (no verdict on the configuration). */
    val thermalLimitedStatus: Int = ThermalStatus.MODERATE,
    /** A probe that reaches this status ends the run (docs/research/open-camera-fv5.md §6.1: abort at MODERATE). */
    val abortThermalStatus: Int = ThermalStatus.MODERATE,
    /** Extra time over warm-up + measurement before the watchdog cancels a probe and records [ProbeOutcome.TIMEOUT]. */
    val watchdogSlackMs: Long = 4_000,
    /** How long to wait for a warm phone to cool before giving up the run, and how often to look. */
    val thermalWaitMs: Long = 30_000,
    val thermalPollMs: Long = 5_000,
    /** Held = at least this share of the target rate with zero drops (D10); a marginal run is measured again (D83). */
    val fpsTolerance: Double = 0.98,
    /** Try rates above the advertised maximum of a listed size (the advertised map is not trusted, see ProbeRunner). */
    val tryUnadvertisedFps: Boolean = true,
    /**
     * Try ladder sizes the stream map does not list, up to [unadvertisedSizeMaxPixels] (D109). A camera given an unlisted
     * size can deliver every frame on time yet at another size, which the GPU stretches to the frame, so such a probe
     * first checks the size of the frames it really gets (D110).
     */
    val tryUnadvertisedSizes: Boolean = true,
    val unadvertisedSizeMaxPixels: Long = 3840L * 2160,
    /** Add each camera's real-size and upscaled rungs to [ladder] ([CameraLadder], D112). */
    val sensorSizeRungs: Boolean = true,
    /** Rungs of at least this many pixels get a no-LUT fallback probe (run only when the LUT version fails on load). */
    val noLutFallbackMinPixels: Long = 3840L * 2160,
    /** Camera errors or timeouts in a row that end the run (another app may hold the camera, or it is wedged). */
    val maxCameraErrors: Int = 2,
    /**
     * Share of the window's nominal frames that a run at the target rate may drop and still be "marginal" (at least one
     * frame, see [marginalDropLimit]): a marginal run is measured once more before it counts as drops, and a marginal
     * verdict implies nothing about heavier configurations (D83).
     */
    val marginalDropShare: Double = 0.005,
    /**
     * A run that loses frames at the full rate is measured again over this window (D103): a few seconds hold one or two
     * of the gaps some camera HALs leave now and then (their own camera apps too), too few to tell a rate from chance.
     */
    val sustainedMeasureMs: Long = 15_000,
    /** Most lost frames, as a share of the sustained window, a mode may have and still hold "with a frame now and then". */
    val tolerableDropShare: Double = 0.01,
    /** Longest step between two frames a mode may show and still hold: a longer one is a visible stutter. */
    val tolerableGapMs: Double = 100.0,
    /** A short run losing more than this share is a clear failure: no sustained measurement. */
    val sustainCandidateDropShare: Double = 0.05
) {
    /** Time one probe that opens the camera takes from the budget. */
    val probeCostMs: Long get() = warmupMs + measureMs + coolDownMs

    /** Time the sustained measurement of a probe takes from the budget. */
    val sustainedProbeCostMs: Long get() = warmupMs + sustainedMeasureMs + coolDownMs

    /** Frames a window of [windowMs] at [fps] should have. */
    fun expectedFrames(fps: Int, windowMs: Long): Double = fps * windowMs / MS_PER_SECOND

    /** Most drops a [measureMs] window at [fps] may have and still be marginal: max(1, [marginalDropShare] of its frames). */
    fun marginalDropLimit(fps: Int): Int = maxOf(1, floor(fps * measureMs / MS_PER_SECOND * marginalDropShare).toInt())

    private companion object {
        const val MS_PER_SECOND = 1_000.0
    }
}
