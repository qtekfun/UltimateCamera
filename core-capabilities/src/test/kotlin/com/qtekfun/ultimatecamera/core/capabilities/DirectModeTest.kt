package com.qtekfun.ultimatecamera.core.capabilities

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Experimental direct 8K mode (D71): the mode decision, the preview size and the probe plan. */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectModeTest {
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/capabilities-test").apply { mkdirs() })

    /** A Pixel 8-like phone that lists 8K (30 fps) on its main camera. */
    private val eightK = Fixtures.oppo.let { d ->
        d.copy(cameras = d.cameras.map { c -> if (c.id == "0") c.copy(videoModes = c.videoModes + AdvertisedMode(7680, 4320, 30)) else c })
    }

    private fun direct(fps: Int) = VideoConfig(7680, 4320, fps, CodecType.HEVC, DynamicRange.HLG10, LutMode.NONE, DirectMode.MODE)

    @Test
    fun `8K always records direct, lower sizes only when flagged`() {
        assertTrue(DirectMode.applies(4320, flagged = false))
        assertFalse(DirectMode.applies(2160, flagged = false))
        assertTrue(DirectMode.applies(2160, flagged = true))
        assertFalse(DirectMode.applies(1080, flagged = false))
    }

    @Test
    fun `preview stream keeps the aspect at most 1920 wide with even sides`() {
        assertEquals(1920 to 1080, DirectMode.previewSize(7680, 4320))
        assertEquals(1920 to 1080, DirectMode.previewSize(3840, 2160))
        assertEquals(1920 to 822, DirectMode.previewSize(7680, 3288))
        assertEquals(1280 to 720, DirectMode.previewSize(1280, 720))
    }

    @Test
    fun `direct rungs are planned only with the experimental switch, last, 8K30 before 8K60, no LUT`() {
        val planner = ProbePlanner()
        assertTrue(planner.plan(eightK).none { DirectMode.isDirect(it.config) })
        val plan = planner.plan(eightK, direct = true)
        val directs = plan.filter { DirectMode.isDirect(it.config) }
        assertEquals(listOf(direct(30), direct(60)), directs.map { it.config })
        assertTrue(plan.takeLast(2).all { DirectMode.isDirect(it.config) })
        assertTrue(directs.all { it.preset == null })
        assertTrue(directs[0].advertised)
        assertFalse(directs[1].advertised) // 8K60 is tried although the map lists 30 fps at most
        // A camera that lists no 8K output: recorded as skipped, never opened.
        val oppoDirect = planner.plan(Fixtures.oppo, direct = true).filter { DirectMode.isDirect(it.config) }
        assertTrue(oppoDirect.all { it.preset == ProbeOutcome.NOT_ADVERTISED_SKIPPED })
    }

    @Test
    fun `engine runs direct variants plainly and a held 8K30 does not stand for 8K60`() = runTest {
        // Pixel 8 expectation of the lead: 8K30 direct holds, 8K60 is refused by the HAL.
        val runner = FakeRunner { r ->
            when {
                DirectMode.isDirect(r.config) && r.config.fps > 30 -> ProbeMeasurement(SessionStatus.SESSION_REJECTED, error = "8K60")
                else -> Fixtures.held(r.config.fps.toDouble())
            }
        }
        val store = CapabilityStore(File(folder.root, CapabilityStore.FILE_NAME))
        val engine = ProbeEngine(
            runner = runner,
            store = store,
            thermal = ThermalMonitor { ThermalStatus.NONE },
            clock = MonotonicClock { testScheduler.currentTime },
            policy = ProbePolicy(budgetMs = 1_000_000),
            direct = true,
            wallClock = { 1_760_000_000_000 },
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )
        val run = engine.run(eightK, listOf("0"))
        val directRequests = runner.requests.filter { DirectMode.isDirect(it.config) }
        assertEquals(listOf(30, 60), directRequests.map { it.config.fps })
        assertTrue(directRequests.all { it.vendorParameters.isEmpty() && it.sessionType == null })
        val selector = CapabilitySelector(run.capabilities)
        assertEquals(listOf(direct(30)), selector.heldVariants("0", DirectMode.MODE))
        // Direct results never enter the GL ladder.
        assertTrue(selector.heldLadder("0").none { it.height == 4320 })
    }
}
