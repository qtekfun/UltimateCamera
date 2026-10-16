package com.qtekfun.ultimatecamera.core.capabilities

import com.qtekfun.ultimatecamera.core.capabilities.CodecType.HEVC
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange.HLG10
import com.qtekfun.ultimatecamera.core.capabilities.DynamicRange.STANDARD
import com.qtekfun.ultimatecamera.core.capabilities.LutMode.HARDWARE
import com.qtekfun.ultimatecamera.core.capabilities.LutMode.NONE
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapabilitySelectorTest {
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/capabilities-test").apply { mkdirs() })

    private val uhd60Hlg = VideoConfig(3840, 2160, 60, HEVC, HLG10, HARDWARE)
    private val uhd30Hlg = VideoConfig(3840, 2160, 30, HEVC, HLG10, HARDWARE)
    private val uhd60 = VideoConfig(3840, 2160, 60, HEVC, STANDARD, HARDWARE)
    private val fhd60 = VideoConfig(1920, 1080, 60, HEVC, STANDARD, HARDWARE)
    private val fhd30 = VideoConfig(1920, 1080, 30, HEVC, STANDARD, HARDWARE)

    private fun probe(device: AdvertisedDevice, truth: (ProbeRequest) -> ProbeMeasurement): DeviceCapabilities {
        var caps: DeviceCapabilities? = null
        runTest {
            val store = CapabilityStore(File(folder.newFolder(), CapabilityStore.FILE_NAME))
            val engine = ProbeEngine(
                FakeRunner(truth),
                store,
                { ThermalStatus.NONE },
                { testScheduler.currentTime },
                ioDispatcher = StandardTestDispatcher(testScheduler)
            )
            caps = engine.run(device).capabilities
        }
        return caps!!
    }

    @Test
    fun `Pixel 8 - best is 4K30 10-bit, and the ladder steps down by load, never up`() {
        val selector = CapabilitySelector(probe(Fixtures.pixel8.device, Fixtures.pixelTruth))
        assertEquals(uhd30Hlg, selector.best("0"))
        assertEquals(listOf(uhd30Hlg, uhd60, fhd60, fhd30), selector.heldLadder("0"))
        // From 4K30 10-bit the next lighter rung is 1080p60, not 4K60 8-bit (heavier).
        assertEquals(listOf(fhd60, fhd30), selector.degradationLadder("0", uhd30Hlg))
        assertEquals(listOf(uhd30Hlg, fhd60, fhd30), selector.degradationLadder("0", uhd60))
        assertFalse(selector.held("0", uhd60Hlg))
        assertNull(selector.nextLower("0", fhd30))
    }

    @Test
    fun `OPPO - best is the base target although it is not advertised`() {
        val caps = probe(Fixtures.oppo, Fixtures.oppoTruth)
        val selector = CapabilitySelector(caps)
        assertEquals(uhd60Hlg, selector.best("0"))
        assertFalse(caps.camera("0")!!.result(uhd60Hlg)!!.advertised)
        assertEquals(listOf(uhd30Hlg, fhd60, fhd30), selector.degradationLadder("0", uhd60Hlg))
    }

    @Test
    fun `flat recording ladder - a held LUT configuration implies its no-LUT version`() {
        // Holds 4K60 10-bit only without the LUT pass.
        val caps = probe(Fixtures.oppo) { r ->
            if (r.config == uhd60Hlg) ProbeMeasurement(SessionStatus.RAN, measuredFps = 57.0, droppedFrames = 40) else Fixtures.oppoTruth(r)
        }
        val selector = CapabilitySelector(caps)
        assertEquals(uhd30Hlg, selector.best("0"))
        assertEquals(uhd60Hlg.copy(lut = NONE), selector.best("0", NONE))
        assertTrue(selector.held("0", uhd30Hlg.copy(lut = NONE)))
    }

    @Test
    fun `nothing probed means nothing selected`() {
        val empty = DeviceCapabilities(identity = Fixtures.oppo.identity, engineVersion = 1, cameras = listOf(CameraCapabilities(Fixtures.oppo.cameras[0])))
        val selector = CapabilitySelector(empty)
        assertNull(selector.best("0"))
        assertNull(selector.best("9"))
        assertTrue(selector.degradationLadder("0", uhd60Hlg).isEmpty())
    }
}
