package com.qtekfun.ultimatecamera.core.capabilities

import java.io.File
import kotlinx.coroutines.delay

/** Real devices: the Pixel 8 from its catalogue seed, the OPPO CPH2841 from the measurements of 2026-10-16. */
object Fixtures {
    private val seedsDir = File(System.getProperty("catalog.devices.dir") ?: "../catalog/devices")

    fun seed(name: String): CatalogSeed = CatalogSeed.parse(File(seedsDir, name).readText())

    val pixel8: CatalogSeed by lazy { seed("google-pixel-8.json") }

    /**
     * OPPO CPH2841 (SM8850, SDK 36): its stream map lists 3840x2160 at 30 fps at most, yet a 4K60 10-bit HLG + LUT
     * session (AE 60-60 in the session parameters) ran at 60.04 fps, zero drops, 300 s, thermal status 0.
     */
    val oppo = AdvertisedDevice(
        // Codename, build id and fingerprint are placeholders (no seed for this phone yet); only model and SDK are real.
        identity = DeviceIdentity("OPPO", "OPPO", "CPH2841", "fixture", 36, buildId = "fixture-build", fingerprint = "fixture/CPH2841:16/A"),
        cameras = listOf(
            AdvertisedCamera(
                id = "0",
                facing = "BACK",
                videoModes = listOf(AdvertisedMode(3840, 2160, 30), AdvertisedMode(1920, 1080, 60), AdvertisedMode(1280, 720, 60)),
                tenBitProfiles = listOf(DynamicRange.HLG10),
                recommendedTenBitProfile = DynamicRange.HLG10
            ),
            AdvertisedCamera(id = "1", facing = "FRONT", videoModes = listOf(AdvertisedMode(1920, 1080, 30)))
        ),
        encoders = listOf(
            AdvertisedEncoder("hevc.fixture", CodecType.HEVC, tenBit = true),
            AdvertisedEncoder("avc.fixture", CodecType.H264, tenBit = false)
        )
    )

    fun label(w: Int, h: Int, fps: Int, codec: CodecType, tenBit: Boolean, lut: LutMode = LutMode.HARDWARE) =
        VideoConfig(w, h, fps, codec, if (tenBit) DynamicRange.HLG10 else DynamicRange.STANDARD, lut).label

    fun held(fps: Double, thermal: Int = ThermalStatus.NONE) = ProbeMeasurement(SessionStatus.RAN, measuredFps = fps, thermalStatusMax = thermal)

    /** Pixel 8 truth from its seed: the HAL refuses 10-bit HLG above 30 fps; everything else holds. */
    val pixelTruth: (ProbeRequest) -> ProbeMeasurement = { r ->
        if (r.config.tenBit &&
            r.config.fps > 30
        ) {
            ProbeMeasurement(SessionStatus.SESSION_REJECTED, error = "session configuration failed")
        } else {
            held(r.config.fps - 0.3)
        }
    }

    /** OPPO truth: everything holds, 4K60 10-bit LUT at 60.04 fps. */
    val oppoTruth: (ProbeRequest) -> ProbeMeasurement = { r -> held(if (r.config.fps == 60) 60.04 else r.config.fps.toDouble()) }
}

/** Plays a phone: answers from [truth] after taking the probe's time on the (virtual) clock. */
class FakeRunner(private val truth: (ProbeRequest) -> ProbeMeasurement) : ProbeRunner {
    val requests = mutableListOf<ProbeRequest>()
    val labels get() = requests.map { it.config.label }

    override suspend fun run(request: ProbeRequest): ProbeMeasurement {
        requests += request
        val answer = truth(request)
        // A refused session fails at configure time, well before the measured window.
        delay(if (answer.status == SessionStatus.RAN) request.warmupMs + request.measureMs else 500)
        return answer
    }
}
