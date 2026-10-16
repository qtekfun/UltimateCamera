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
}
