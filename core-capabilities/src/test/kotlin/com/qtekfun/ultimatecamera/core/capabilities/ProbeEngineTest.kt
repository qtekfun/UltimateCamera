package com.qtekfun.ultimatecamera.core.capabilities

import com.qtekfun.ultimatecamera.core.capabilities.CodecType.HEVC
import com.qtekfun.ultimatecamera.core.capabilities.Fixtures.label
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeEngineTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/capabilities-test").apply { mkdirs() })

    private val store by lazy { CapabilityStore(File(folder.root, CapabilityStore.FILE_NAME)) }

    private fun TestScope.engine(
        runner: ProbeRunner,
        policy: ProbePolicy = ProbePolicy(),
        thermal: ThermalMonitor = ThermalMonitor { ThermalStatus.NONE },
        vendor: VendorExtension = NoVendorExtension
    ) = ProbeEngine(
        runner = runner,
        store = store,
        thermal = thermal,
        clock = MonotonicClock { testScheduler.currentTime },
        policy = policy,
        vendor = vendor,
        wallClock = { 1_760_000_000_000 },
        ioDispatcher = StandardTestDispatcher(testScheduler)
    )

    private fun ProbeRun.outcome(label: String) = capabilities.camera("0")!!.results.single { it.config.label == label }.outcome

    @Test
    fun `Pixel 8 - the HAL refusal of 10-bit above 30 fps is found and the plan stops there`() = runTest {
        val runner = FakeRunner(Fixtures.pixelTruth)
        val run = engine(runner).run(Fixtures.pixel8.device)

        // Five sessions; every no-LUT fallback is implied by its LUT version (held or refused).
        assertEquals(
            listOf(
                label(1920, 1080, 30, HEVC, false),
                label(1920, 1080, 60, HEVC, false),
                label(3840, 2160, 30, HEVC, true),
                label(3840, 2160, 60, HEVC, false),
                label(3840, 2160, 60, HEVC, true)
            ),
            runner.labels
        )
        assertNull(run.stopReason)
        assertTrue(run.capabilities.complete)
        assertEquals(ProbeOutcome.SESSION_REJECTED, run.outcome(label(3840, 2160, 60, HEVC, true)))
        assertEquals(ProbeOutcome.IMPLIED_FAILURE, run.outcome(label(3840, 2160, 60, HEVC, true, LutMode.NONE)))
        assertEquals(ProbeOutcome.IMPLIED_HELD, run.outcome(label(3840, 2160, 30, HEVC, true, LutMode.NONE)))
        // Same verdicts as the spike's catalogue seed (size, rate and bit depth; the spike ran 1080p30 with H.264).
        val seed = Fixtures.pixel8
        val seedHeld = seed.heldLabels.map { it.split(" ").let { p -> "${p[0]} ${p[2]}" } }
        run.capabilities.camera("0")!!.results.filter { it.outcome == ProbeOutcome.ACCEPTED_AND_HELD }.forEach {
            assertTrue(it.config.label, it.config.label.split(" ").let { p -> "${p[0]} ${p[2]}" } in seedHeld)
        }
        assertTrue(label(3840, 2160, 60, HEVC, true) in seed.rejectedLabels)
        // Well inside the budget: 4 runs of 5 s, a 0.5 s refusal and 2 s pauses.
        assertEquals(4 * 7_000L + 2_500L, run.elapsedMs)
        assertEquals(run.capabilities, (store.load() as StoreLoad.Loaded).capabilities)
    }

    @Test
    fun `OPPO - unadvertised 4K60 10-bit is tried, holds and is recorded as unadvertised`() = runTest {
        val runner = FakeRunner(Fixtures.oppoTruth)
        val run = engine(runner).run(Fixtures.oppo)
        val top = run.capabilities.camera("0")!!.result(VideoConfig(3840, 2160, 60, HEVC, DynamicRange.HLG10, LutMode.HARDWARE))!!
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, top.outcome)
        assertFalse(top.advertised)
        assertEquals(60.04, top.measuredFps!!, 1e-9)
        assertEquals(0, top.droppedFrames)
        assertEquals(ThermalStatus.NONE, top.thermalStatusMax)
        assertEquals(5, runner.requests.size)
        assertTrue(run.capabilities.complete)
    }

    @Test
    fun `a modest phone that drops at 4K30 skips every 4K60 probe`() = runTest {
        // Holds up to 1080p60, drops from 4K30 10-bit up (with or without the LUT).
        val runner = FakeRunner { r ->
            if (r.config.pixels >
                1920 * 1080
            ) {
                ProbeMeasurement(SessionStatus.RAN, measuredFps = r.config.fps * 0.9, droppedFrames = 12)
            } else {
                Fixtures.held(r.config.fps.toDouble())
            }
        }
        val run = engine(runner).run(Fixtures.oppo)
        assertEquals(
            listOf(
                label(1920, 1080, 30, HEVC, false),
                label(1920, 1080, 60, HEVC, false),
                label(3840, 2160, 30, HEVC, true),
                label(3840, 2160, 30, HEVC, true, LutMode.NONE)
            ),
            runner.labels
        )
        listOf(
            label(3840, 2160, 60, HEVC, false),
            label(3840, 2160, 60, HEVC, false, LutMode.NONE),
            label(3840, 2160, 60, HEVC, true),
            label(3840, 2160, 60, HEVC, true, LutMode.NONE)
        ).forEach { assertEquals(it, ProbeOutcome.IMPLIED_FAILURE, run.outcome(it)) }
        assertTrue(run.capabilities.complete)
        assertEquals(VideoConfig(1920, 1080, 60, HEVC, DynamicRange.STANDARD, LutMode.HARDWARE), CapabilitySelector(run.capabilities).best("0"))
    }

    @Test
    fun `a camera running slower than asked is rate-limited, not drops, and implies nothing`() = runTest {
        // vivo X500 Pro Max: 1080p60 delivered 30.0 fps; every missing frame also counted as a gap ("121 drops").
        val runner = FakeRunner { r ->
            if (r.config.width == 1920 && r.config.fps == 60) {
                ProbeMeasurement(SessionStatus.RAN, measuredFps = 30.0, droppedFrames = 121, cameraFps = 30.0)
            } else {
                Fixtures.held(r.config.fps.toDouble()).copy(cameraFps = r.config.fps.toDouble())
            }
        }
        val run = engine(runner).run(Fixtures.oppo)
        val r = run.capabilities.camera("0")!!.results.first { it.config.label == label(1920, 1080, 60, HEVC, false) }
        assertEquals(ProbeOutcome.CAMERA_RATE_LIMITED, r.outcome)
        assertFalse(r.isDefinitive)
        // The 4K60 rows are still probed, not implied failing from the camera's own rate.
        assertTrue(label(3840, 2160, 60, HEVC, true) in runner.labels)
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, run.outcome(label(3840, 2160, 60, HEVC, true)))
    }

    @Test
    fun `pipeline drops with the camera at full rate are still drops`() = runTest {
        val runner = FakeRunner { r ->
            if (r.config.fps == 60) {
                ProbeMeasurement(SessionStatus.RAN, measuredFps = 50.0, droppedFrames = 40, cameraFps = 60.0)
            } else {
                Fixtures.held(r.config.fps.toDouble())
            }
        }
        val run = engine(runner).run(Fixtures.oppo)
        assertEquals(ProbeOutcome.DROPS, run.outcome(label(1920, 1080, 60, HEVC, false)))
        assertEquals(ProbeOutcome.IMPLIED_FAILURE, run.outcome(label(3840, 2160, 60, HEVC, true)))
    }

    @Test
    fun `low frame rate without drops is not held`() = runTest {
        val runner = FakeRunner { r -> Fixtures.held(r.config.fps * 0.95) }
        val run = engine(runner).run(Fixtures.oppo)
        assertEquals(ProbeOutcome.LOW_FPS, run.outcome(label(1920, 1080, 30, HEVC, false)))
        assertEquals(1, runner.requests.size)
        assertNull(CapabilitySelector(run.capabilities).best("0"))
    }

    @Test
    fun `thermal guard - a warm phone waits, then gives up without opening the camera`() = runTest {
        val runner = FakeRunner(Fixtures.oppoTruth)
        val policy = ProbePolicy()
        val run = engine(runner, policy, thermal = ThermalMonitor { ThermalStatus.MODERATE }).run(Fixtures.oppo)
        assertTrue(runner.requests.isEmpty())
        assertEquals(StopReason.THERMAL, run.stopReason)
        assertEquals(policy.thermalWaitMs, run.elapsedMs)
        assertFalse(run.capabilities.complete)
        assertTrue(run.capabilities.camera("0")!!.results.all { it.outcome == ProbeOutcome.SKIPPED_THERMAL })
    }

    @Test
    fun `thermal guard - waits for the phone to cool before the next probe`() = runTest {
        val runner = FakeRunner(Fixtures.oppoTruth)
        // Warm for the first 12 s of the run.
        val thermal = ThermalMonitor { if (testScheduler.currentTime < 12_000) ThermalStatus.MODERATE else ThermalStatus.NONE }
        val run = engine(runner, thermal = thermal).run(Fixtures.oppo)
        assertNull(run.stopReason)
        assertEquals(5, runner.requests.size)
        assertEquals(15_000L + 5 * 7_000L, run.elapsedMs)
    }

    @Test
    fun `thermal guard - a probe reaching SEVERE ends the run`() = runTest {
        val runner = FakeRunner { r -> Fixtures.held(r.config.fps.toDouble(), thermal = if (r.config.fps == 60) ThermalStatus.SEVERE else ThermalStatus.NONE) }
        val run = engine(runner).run(Fixtures.oppo)
        assertEquals(StopReason.THERMAL, run.stopReason)
        assertEquals(2, runner.requests.size)
        assertEquals(ProbeOutcome.SKIPPED_THERMAL, run.outcome(label(3840, 2160, 30, HEVC, true)))
    }

    @Test
    fun `drops on a warm phone are thermal-limited, imply nothing and are probed again`() = runTest {
        val warmDrops = FakeRunner { r ->
            if (r.config.fps == 60) {
                ProbeMeasurement(SessionStatus.RAN, measuredFps = 55.0, droppedFrames = 30, thermalStatusMax = ThermalStatus.MODERATE)
            } else {
                Fixtures.held(r.config.fps.toDouble())
            }
        }
        // Abort only at SEVERE here, to see what the MODERATE drop implies (with the default the run would stop).
        val run = engine(warmDrops, ProbePolicy(abortThermalStatus = ThermalStatus.SEVERE)).run(Fixtures.oppo)
        val r = run.capabilities.camera("0")!!.results.first { it.config.label == label(1920, 1080, 60, HEVC, false) }
        assertEquals(ProbeOutcome.DROPS, r.outcome)
        assertTrue(r.thermalLimited)
        // 4K30 10-bit is still probed (the 1080p60 drop says nothing), and the catalogue stays incomplete.
        assertTrue(label(3840, 2160, 30, HEVC, true) in warmDrops.labels)
        assertFalse(run.capabilities.complete)

        val cool = FakeRunner(Fixtures.oppoTruth)
        engine(cool).run(Fixtures.oppo)
        assertTrue(label(1920, 1080, 60, HEVC, false) in cool.labels)
        assertFalse(label(1920, 1080, 30, HEVC, false) in cool.labels)
    }

    @Test
    fun `budget - no probe starts past it, and the next run resumes`() = runTest {
        val runner = FakeRunner(Fixtures.oppoTruth)
        val run = engine(runner, ProbePolicy(budgetMs = 15_000)).run(Fixtures.oppo)
        assertEquals(StopReason.BUDGET, run.stopReason)
        assertEquals(2, runner.requests.size)
        assertTrue(run.elapsedMs <= 15_000)
        assertEquals(ProbeOutcome.SKIPPED_BUDGET, run.outcome(label(3840, 2160, 30, HEVC, true)))

        val second = FakeRunner(Fixtures.oppoTruth)
        val resumed = engine(second).run(Fixtures.oppo)
        assertEquals(3, second.requests.size)
        assertFalse(label(1920, 1080, 30, HEVC, false) in second.labels)
        assertTrue(resumed.capabilities.complete)
    }

    @Test
    fun `cancellation keeps what was measured`() = runTest {
        val runner = FakeRunner(Fixtures.oppoTruth)
        val job = async { engine(runner).run(Fixtures.oppo) }
        advanceTimeBy(8_000) // first probe (5 s) and its pause done, second running
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        val saved = (store.load() as StoreLoad.Loaded).capabilities
        assertFalse(saved.complete)
        assertEquals(listOf(ProbeOutcome.ACCEPTED_AND_HELD), saved.camera("0")!!.results.map { it.outcome })
        assertEquals(ReprobeReason.INCOMPLETE, saved.reprobeReason(Fixtures.oppo.identity))

        val second = FakeRunner(Fixtures.oppoTruth)
        engine(second).run(Fixtures.oppo)
        assertEquals(4, second.requests.size)
    }

    @Test
    fun `a probe that kills the app is recorded as crashed on the next run and never retried`() = runTest {
        val uhd30Hlg = label(3840, 2160, 30, HEVC, true)
        val dying = FakeRunner { r -> if (r.config.label == uhd30Hlg) error("process died") else Fixtures.oppoTruth(r) }
        val died = runCatching { engine(dying).run(Fixtures.oppo) }
        assertTrue(died.exceptionOrNull() is IllegalStateException)
        assertEquals(
            InProgressProbe("0", VideoConfig(3840, 2160, 30, HEVC, DynamicRange.HLG10, LutMode.HARDWARE)),
            (store.load() as StoreLoad.Loaded).capabilities.inProgress
        )

        val next = FakeRunner(Fixtures.oppoTruth)
        val run = engine(next).run(Fixtures.oppo)
        assertEquals(ProbeOutcome.CRASHED, run.outcome(uhd30Hlg))
        // Nothing 10-bit and at least as heavy is tried after a crash; 4K60 8-bit still is.
        assertEquals(listOf(label(3840, 2160, 60, HEVC, false)), next.labels)
        assertEquals(ProbeOutcome.IMPLIED_FAILURE, run.outcome(label(3840, 2160, 60, HEVC, true)))
        assertNull(run.capabilities.inProgress)
        assertTrue(run.capabilities.complete)
    }

    @Test
    fun `the watchdog turns a hung probe into a timeout`() = runTest {
        val oppo = FakeRunner(Fixtures.oppoTruth)
        // 4K30 never answers.
        val runner = ProbeRunner { r -> if (r.config.width == 3840 && r.config.fps == 30) awaitCancellation() else oppo.run(r) }
        val run = engine(runner).run(Fixtures.oppo)
        assertEquals(ProbeOutcome.TIMEOUT, run.outcome(label(3840, 2160, 30, HEVC, true)))
        assertEquals(ProbeOutcome.IMPLIED_FAILURE, run.outcome(label(3840, 2160, 30, HEVC, true, LutMode.NONE)))
        assertEquals(ProbeOutcome.IMPLIED_FAILURE, run.outcome(label(3840, 2160, 60, HEVC, true)))
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, run.outcome(label(3840, 2160, 60, HEVC, false)))
        // Two 1080p probes with pauses, the 9 s watchdog and its pause, then 4K60 8-bit and its pause.
        assertEquals(2 * 7_000L + 9_000L + 2_000L + 7_000L, run.elapsedMs)
    }

    @Test
    fun `a hang is kept by an automatic run and measured again when the user asks`() = runTest {
        val healthy = FakeRunner(Fixtures.oppoTruth)
        val uhd30Hlg = label(3840, 2160, 30, HEVC, true)
        val hanging = ProbeRunner { r -> if (r.config.width == 3840 && r.config.fps == 30) awaitCancellation() else healthy.run(r) }
        assertEquals(ProbeOutcome.TIMEOUT, engine(hanging).run(Fixtures.oppo).outcome(uhd30Hlg))

        // An automatic run keeps the hang and what it implied: nothing is opened again.
        val automatic = FakeRunner(Fixtures.oppoTruth)
        val kept = engine(automatic).run(Fixtures.oppo)
        assertEquals(ProbeOutcome.TIMEOUT, kept.outcome(uhd30Hlg))
        assertEquals(emptyList<String>(), automatic.labels)

        // A run the user asked for measures the hung mode and the modes it implied again (D100).
        val asked = FakeRunner(Fixtures.oppoTruth)
        val run = engine(asked).run(Fixtures.oppo, retryHangs = true)
        assertTrue(uhd30Hlg in asked.labels)
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, run.outcome(uhd30Hlg))
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, run.outcome(label(3840, 2160, 60, HEVC, true)))
    }

    @Test
    fun `repeated camera errors end the run without recording guesses`() = runTest {
        val runner = FakeRunner { ProbeMeasurement(SessionStatus.CAMERA_ERROR, error = "camera 0 error 4") }
        val run = engine(runner).run(Fixtures.oppo)
        assertEquals(StopReason.CAMERA_ERRORS, run.stopReason)
        assertEquals(2, runner.requests.size)
        assertEquals(2, run.capabilities.camera("0")!!.results.size)
        assertFalse(run.capabilities.complete)
    }

    @Test
    fun `an encoder that refuses the format implies the heavier configurations of that codec`() = runTest {
        val runner = FakeRunner { r ->
            if (r.config.tenBit) ProbeMeasurement(SessionStatus.ENCODER_UNSUPPORTED, error = "configure failed") else Fixtures.held(r.config.fps.toDouble())
        }
        val run = engine(runner).run(Fixtures.oppo)
        assertEquals(ProbeOutcome.ENCODER_UNSUPPORTED, run.outcome(label(3840, 2160, 30, HEVC, true)))
        assertEquals(ProbeOutcome.IMPLIED_FAILURE, run.outcome(label(3840, 2160, 60, HEVC, true)))
        assertEquals(1, runner.labels.count { it.contains("10-bit") })
    }

    @Test
    fun `vendor parameters are used when they work and dropped when the HAL refuses them`() = runTest {
        val vendor = object : VendorExtension {
            override val id = "test-vendor"
            override fun appliesTo(identity: DeviceIdentity, camera: AdvertisedCamera) = identity.manufacturer == "OPPO"
            override fun sessionParameters(camera: AdvertisedCamera, config: VideoConfig) =
                listOf(VendorParameter("com.example.vendor.mode", VendorValueType.INT, "1"))
        }
        // The fake HAL refuses vendor parameters at 4K and accepts them below.
        val runner = FakeRunner { r ->
            if (r.vendorParameters.isNotEmpty() && r.config.width == 3840) ProbeMeasurement(SessionStatus.SESSION_REJECTED) else Fixtures.oppoTruth(r)
        }
        val run = engine(runner, vendor = vendor).run(Fixtures.oppo)
        val results = run.capabilities.camera("0")!!.results.associateBy { it.config.label }
        assertEquals("test-vendor", results.getValue(label(1920, 1080, 30, HEVC, false)).vendorExtension)
        val fourK = results.getValue(label(3840, 2160, 30, HEVC, true))
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, fourK.outcome)
        assertNull(fourK.vendorExtension)
        assertEquals(5 + 3, run.sessionsRun)
    }

    @Test
    fun `a vendor variant is probed with its parameters only and a refusal is never retried plain`() = runTest {
        val oppo = Fixtures.oppo.copy(cameras = Fixtures.oppo.cameras.map { it.copy(vendorRequestKeys = listOf(SensorHdrExtension.QTI_VHDR_MODE)) })
        // The fake HAL refuses the vendor HDR key at 60 fps and takes it at 30.
        val runner = FakeRunner { r ->
            if (r.vendorParameters.isNotEmpty() && r.config.fps == 60) ProbeMeasurement(SessionStatus.SESSION_REJECTED) else Fixtures.oppoTruth(r)
        }
        val run = engine(runner, vendor = SensorHdrExtension).run(oppo)
        val variants = run.capabilities.camera("0")!!.results.filter { it.config.vendorMode == SensorHdrExtension.MODE }.associateBy { it.config.fps }
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, variants.getValue(30).outcome)
        assertEquals(SensorHdrExtension.id, variants.getValue(30).vendorExtension)
        assertEquals(ProbeOutcome.SESSION_REJECTED, variants.getValue(60).outcome)
        // Plain probes never got the vendor key, variants always did.
        assertTrue(runner.requests.all { (it.config.vendorMode != null) == it.vendorParameters.isNotEmpty() })
    }

    @Test
    fun `combined extensions record the owning part and a use case variant runs without vendor keys`() = runTest {
        // D104: sensor HDR and frame pacing in one run.
        val keys = listOf(SensorHdrExtension.QTI_VHDR_MODE, PacingVariants.QTI_EXTRA_PREVIEW_BUFFERS)
        val device = Fixtures.oppo.copy(cameras = Fixtures.oppo.cameras.map { it.copy(vendorRequestKeys = keys, streamUseCase = true) })
        val runner = FakeRunner(Fixtures.oppoTruth)
        val run = engine(runner, vendor = CombinedVendorExtension(SensorHdrExtension, PacingVariants)).run(device)
        val results = run.capabilities.camera("0")!!.results
        val hdr = results.filter { it.config.vendorMode == SensorHdrExtension.MODE }
        assertEquals(listOf(30, 60), hdr.map { it.config.fps })
        assertTrue(hdr.all { it.vendorExtension == SensorHdrExtension.id && it.outcome == ProbeOutcome.ACCEPTED_AND_HELD })
        val pacing = results.filter { it.config.vendorMode in PacingVariants.variants(device.cameras[0]) }
        assertEquals(listOf(PacingVariants.QTI_EIS, PacingVariants.QTI_BUFFERS, PacingVariants.RECORD_USE_CASE), pacing.map { it.config.vendorMode })
        assertTrue(pacing.all { it.config.fps == 60 && it.vendorExtension == PacingVariants.id && it.outcome == ProbeOutcome.ACCEPTED_AND_HELD })
        val useCase = runner.requests.single { it.config.vendorMode == PacingVariants.RECORD_USE_CASE }
        assertTrue(useCase.recordUseCase)
        assertTrue(useCase.vendorParameters.isEmpty())
        assertNull(useCase.sessionType)
        assertEquals(PacingVariants.QTI_EIS_VIDEO_SESSION, runner.requests.single { it.config.vendorMode == PacingVariants.QTI_EIS }.sessionType)
        // Only that variant asks for the use case; plain probes get nothing from either part.
        assertEquals(1, runner.requests.count { it.recordUseCase })
        assertTrue(runner.requests.filter { it.config.vendorMode == null }.all { it.vendorParameters.isEmpty() && it.sessionType == null })
        assertEquals(uhd60TenBit, CapabilitySelector(run.capabilities).best("0"))
    }

    @Test
    fun `buffers the camera reports lost are kept and named in the summary, without changing the verdict`() = runTest {
        val runner = FakeRunner { r ->
            when {
                r.config.fps != 60 || r.config.width != 3840 -> Fixtures.oppoTruth(r)
                r.measureMs == 4_000L -> ProbeMeasurement(
                    SessionStatus.RAN,
                    measuredFps = 59.0,
                    droppedFrames = 4,
                    cameraFps = 60.0,
                    gapCount = 2,
                    maxGapMs = 50.0,
                    buffersLost = 2
                )
                else -> ProbeMeasurement(
                    SessionStatus.RAN,
                    measuredFps = 59.6,
                    droppedFrames = 6,
                    cameraFps = 60.0,
                    gapCount = 4,
                    maxGapMs = 50.0,
                    buffersLost = 5
                )
            }
        }
        val uhd60 = engine(runner).run(Fixtures.oppo).result(label(3840, 2160, 60, HEVC, false))
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, uhd60.outcome)
        assertEquals(5, uhd60.buffersLost)
        assertTrue(uhd60.reason!!, uhd60.reason!!.contains("largest 50.0 ms, 5 buffers lost"))
        assertTrue(uhd60.reason!!, uhd60.reason!!.contains("first run drops (59.0 fps, 4 dropped, 2 gaps, largest 50.0 ms, 2 buffers lost)"))
        // None lost: nothing said.
        val clean = engine(FakeRunner(Fixtures.oppoTruth)).run(Fixtures.oppo, resume = false)
        assertTrue(clean.capabilities.camera("0")!!.results.none { it.reason.orEmpty().contains("buffers lost") })
    }

    @Test
    fun `stored results are reused on the same build and probed again after an OS update`() = runTest {
        engine(FakeRunner(Fixtures.oppoTruth)).run(Fixtures.oppo)

        val again = FakeRunner(Fixtures.oppoTruth)
        val reused = engine(again).run(Fixtures.oppo)
        assertTrue(again.requests.isEmpty())
        assertTrue(reused.capabilities.complete)

        val updated = Fixtures.oppo.copy(identity = Fixtures.oppo.identity.copy(fingerprint = "fixture/CPH2841:16/B"))
        val stored = (store.load() as StoreLoad.Loaded).capabilities
        assertEquals(ReprobeReason.OS_CHANGED, stored.reprobeReason(updated.identity))
        assertNull(store.loadFor(updated.identity))
        val afterUpdate = FakeRunner(Fixtures.oppoTruth)
        engine(afterUpdate).run(updated)
        assertEquals(5, afterUpdate.requests.size)

        val forced = FakeRunner(Fixtures.oppoTruth)
        engine(forced).run(updated, resume = false)
        assertEquals(5, forced.requests.size)
    }

    // D83: the OPPO Find X9 Ultra (CPH2841) on v0.7.1 measured 1080p60 at 59.8 fps with 1 dropped frame (one gap in
    // the 4 s window); that DROPS implied every 4K60 row failing, and the best mode fell from 4K60 10-bit to 4K30 10-bit.
    private val oppoOneGap = ProbeMeasurement(SessionStatus.RAN, measuredFps = 59.8, droppedFrames = 1, cameraFps = 60.0, gapCount = 1, maxGapMs = 33.4)

    private val uhd60TenBit = VideoConfig(3840, 2160, 60, HEVC, DynamicRange.HLG10, LutMode.HARDWARE)

    /** OPPO truth, except that the first [marginalRuns] runs of 1080p60 lose one frame in one gap. */
    private fun oppoWithMarginal1080p60(marginalRuns: Int): FakeRunner {
        var left = marginalRuns
        return FakeRunner { r ->
            if (r.config.width == 1920 && r.config.fps == 60 && left > 0) {
                left--
                oppoOneGap
            } else {
                Fixtures.oppoTruth(r)
            }
        }
    }

    private fun ProbeRun.result(label: String) = capabilities.camera("0")!!.results.single { it.config.label == label }

    @Test
    fun `OPPO - one frame gap at 1080p60 is measured again and does not throw away 4K60`() = runTest {
        val runner = oppoWithMarginal1080p60(marginalRuns = 1)
        val run = engine(runner).run(Fixtures.oppo)
        val p1080p60 = label(1920, 1080, 60, HEVC, false)
        // 1080p60 twice (the marginal run and its retry), then the 4K rungs as on v0.6.x.
        assertEquals(
            listOf(
                label(1920, 1080, 30, HEVC, false),
                p1080p60,
                p1080p60,
                label(3840, 2160, 30, HEVC, true),
                label(3840, 2160, 60, HEVC, false),
                label(3840, 2160, 60, HEVC, true)
            ),
            runner.labels
        )
        val retried = run.result(p1080p60)
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, retried.outcome)
        assertFalse(retried.marginal)
        assertTrue(retried.reason!!, retried.reason!!.contains("held over 15 s"))
        assertTrue(retried.reason!!, retried.reason!!.contains("first run marginal (59.8 fps, 1 dropped, 1 gaps, largest 33.4 ms)"))
        // The second 1080p60 run is the sustained one.
        assertEquals(listOf(4_000L, 15_000L), runner.requests.filter { it.config.label == p1080p60 }.map { it.measureMs })
        listOf(
            label(3840, 2160, 60, HEVC, false),
            label(3840, 2160, 60, HEVC, true)
        ).forEach { assertEquals(it, ProbeOutcome.ACCEPTED_AND_HELD, run.outcome(it)) }
        listOf(label(3840, 2160, 60, HEVC, false, LutMode.NONE), label(3840, 2160, 60, HEVC, true, LutMode.NONE))
            .forEach { assertEquals(it, ProbeOutcome.IMPLIED_HELD, run.outcome(it)) }
        assertEquals(uhd60TenBit, CapabilitySelector(run.capabilities).best("0"))
        assertTrue(run.capabilities.complete)
        assertEquals(6, run.sessionsRun)
    }

    @Test
    fun `a frame now and then over the sustained run holds, flagged marginal`() = runTest {
        val runner = oppoWithMarginal1080p60(marginalRuns = 2)
        val run = engine(runner).run(Fixtures.oppo)
        val p1080p60 = label(1920, 1080, 60, HEVC, false)
        val r = run.result(p1080p60)
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, r.outcome)
        assertTrue(r.marginal)
        assertTrue(r.isDefinitive)
        assertEquals(1, r.gapCount)
        assertEquals(33.4, r.maxGapMs!!, 1e-9)
        assertTrue(r.reason!!, r.reason!!.startsWith("held with occasional drops over 15 s"))
        assertEquals(2, runner.labels.count { it == p1080p60 })
        assertTrue(label(3840, 2160, 60, HEVC, true) in runner.labels)
        assertEquals(uhd60TenBit, CapabilitySelector(run.capabilities).best("0"))
        // A marginal DROPS (no sustained run) still implies nothing; a plain one does.
        val drops = r.copy(outcome = ProbeOutcome.DROPS)
        assertNull(Implications.decide(uhd60TenBit, listOf(drops)))
        assertEquals(ProbeOutcome.IMPLIED_FAILURE, Implications.decide(uhd60TenBit, listOf(drops.copy(marginal = false)))?.first)
    }

    // D103: a phone whose camera HAL drops a frame or two now and then at 4K60 (its own camera app too) measured 4K60
    // 8-bit at 59.0 fps with 4 lost (largest gap 50 ms) and 59.3 fps with 3 lost (67 ms) in the 4 s window, on the GPU
    // and on the direct path alike. Both failed, implied every 4K60 10-bit row failing, and the best mode fell to 4K30,
    // while a 15-minute 4K60 10-bit take lost 0.47 %.
    private fun halGaps(sustainedDropped: Int, sustainedGapMs: Double = 50.0) = FakeRunner { r ->
        when {
            r.config.fps != 60 || r.config.width != 3840 -> Fixtures.oppoTruth(r)
            r.measureMs == 4_000L -> ProbeMeasurement(SessionStatus.RAN, measuredFps = 59.0, droppedFrames = 4, cameraFps = 60.0, gapCount = 2, maxGapMs = 50.0)
            else -> ProbeMeasurement(
                SessionStatus.RAN,
                measuredFps = 59.6,
                droppedFrames = sustainedDropped,
                cameraFps = 60.0,
                gapCount = 4,
                maxGapMs = sustainedGapMs
            )
        }
    }

    @Test
    fun `occasional camera gaps at 4K60 are measured for 15 s and hold within one percent`() = runTest {
        // 900 frames in 15 s: 6 lost is 0.67 %.
        val runner = halGaps(sustainedDropped = 6)
        val run = engine(runner).run(Fixtures.oppo)
        val uhd60 = run.result(label(3840, 2160, 60, HEVC, false))
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, uhd60.outcome)
        assertTrue(uhd60.marginal)
        assertEquals(6, uhd60.droppedFrames)
        assertTrue(uhd60.reason!!, uhd60.reason!!.contains("first run drops (59.0 fps, 4 dropped, 2 gaps, largest 50.0 ms)"))
        // 4K60 10-bit is measured, not implied failing, and is the best mode.
        assertTrue(label(3840, 2160, 60, HEVC, true) in runner.labels)
        assertEquals(ProbeOutcome.ACCEPTED_AND_HELD, run.outcome(label(3840, 2160, 60, HEVC, true)))
        assertEquals(uhd60TenBit, CapabilitySelector(run.capabilities).best("0"))
        assertTrue(run.capabilities.complete)
    }

    @Test
    fun `more than one percent or a long gap over the sustained run is still drops and implies`() = runTest {
        listOf(halGaps(sustainedDropped = 12), halGaps(sustainedDropped = 3, sustainedGapMs = 150.0)).forEach { runner ->
            val run = engine(runner).run(Fixtures.oppo, resume = false)
            val uhd60 = run.result(label(3840, 2160, 60, HEVC, false))
            assertEquals(ProbeOutcome.DROPS, uhd60.outcome)
            assertFalse(uhd60.marginal)
            assertTrue(uhd60.reason!!, uhd60.reason!!.startsWith("over 15 s: DROPS"))
            assertEquals(ProbeOutcome.IMPLIED_FAILURE, run.outcome(label(3840, 2160, 60, HEVC, true)))
        }
    }

    @Test
    fun `clear drops at the full rate are not marginal, are not retried and still imply`() = runTest {
        // 40 frames lost, and 2 lost at 60 fps (over max(1, 0.5 % of 240)): both clear failures.
        listOf(40, 2).forEach { dropped ->
            val runner = FakeRunner { r ->
                if (r.config.fps == 60) {
                    ProbeMeasurement(SessionStatus.RAN, measuredFps = 59.9, droppedFrames = dropped, cameraFps = 60.0, gapCount = 1, maxGapMs = 700.0)
                } else {
                    Fixtures.held(r.config.fps.toDouble())
                }
            }
            val run = engine(runner).run(Fixtures.oppo, resume = false)
            val r = run.result(label(1920, 1080, 60, HEVC, false))
            assertEquals(ProbeOutcome.DROPS, r.outcome)
            assertFalse(r.marginal)
            assertEquals(1, runner.labels.count { it == label(1920, 1080, 60, HEVC, false) })
            assertEquals(ProbeOutcome.IMPLIED_FAILURE, run.outcome(label(3840, 2160, 60, HEVC, true)))
        }
    }

    @Test
    fun `a marginal run with no budget left for the retry stays marginal drops and implies nothing`() = runTest {
        // 1080p30 (5 s) + pause, 1080p60 starts at 7 s and ends at 12 s: a retry (2 s pause + 7 s) would end past 15 s.
        val runner = oppoWithMarginal1080p60(marginalRuns = 1)
        val run = engine(runner, ProbePolicy(budgetMs = 15_000)).run(Fixtures.oppo)
        assertEquals(2, runner.requests.size)
        val r = run.result(label(1920, 1080, 60, HEVC, false))
        assertEquals(ProbeOutcome.DROPS, r.outcome)
        assertTrue(r.marginal)
        assertTrue(r.reason!!, r.reason!!.endsWith("retry skipped: budget"))
        assertEquals(StopReason.BUDGET, run.stopReason)
        assertEquals(ProbeOutcome.SKIPPED_BUDGET, run.outcome(label(3840, 2160, 30, HEVC, true)))
        // Nothing implied from it: the next run probes the 4K60 rows.
        val second = FakeRunner(Fixtures.oppoTruth)
        val resumed = engine(second).run(Fixtures.oppo)
        assertTrue(label(3840, 2160, 60, HEVC, true) in second.labels)
        assertEquals(uhd60TenBit, CapabilitySelector(resumed.capabilities).best("0"))
    }

    @Test
    fun `a marginal run on a phone too warm after the pause is not retried`() = runTest {
        var runs = 0
        val runner = FakeRunner { r ->
            runs++
            if (r.config.width == 1920 && r.config.fps == 60) oppoOneGap else Fixtures.oppoTruth(r)
        }
        // Cool for the first two probes' starts, warm when the retry would start.
        val thermal = ThermalMonitor { if (runs >= 2) ThermalStatus.MODERATE else ThermalStatus.NONE }
        val run = engine(runner, ProbePolicy(thermalWaitMs = 0), thermal = thermal).run(Fixtures.oppo)
        val r = run.result(label(1920, 1080, 60, HEVC, false))
        assertTrue(r.marginal)
        assertTrue(r.reason!!, r.reason!!.endsWith("retry skipped: thermal status ${ThermalStatus.MODERATE}"))
        assertEquals(1, runner.labels.count { it == label(1920, 1080, 60, HEVC, false) })
    }

    @Test
    fun `the marginal limit is one frame or half a percent of the window`() {
        val policy = ProbePolicy()
        assertEquals(900.0, policy.expectedFrames(60, policy.sustainedMeasureMs), 1e-9)
        assertEquals(1, policy.marginalDropLimit(30))
        assertEquals(1, policy.marginalDropLimit(60))
        assertEquals(2, policy.marginalDropLimit(120))
        assertEquals(1, ProbePolicy(marginalDropShare = 0.0).marginalDropLimit(60))
    }

    @Test
    fun `catalogues from the engine before marginal retries are probed again`() {
        val old = DeviceCapabilities(identity = Fixtures.oppo.identity, engineVersion = 1, cameras = emptyList(), complete = true)
        assertEquals(ReprobeReason.ENGINE_CHANGED, old.reprobeReason(Fixtures.oppo.identity))
    }
}
