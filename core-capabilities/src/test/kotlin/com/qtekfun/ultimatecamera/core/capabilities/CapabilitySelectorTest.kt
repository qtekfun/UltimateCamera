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
    fun `Pixel 8 - best is 4K60 8-bit, smooth motion before bit depth, and the ladder steps down by load, never up`() {
        val selector = CapabilitySelector(probe(Fixtures.pixel8.device, Fixtures.pixelTruth))
        // D105: 4K60 10-bit is refused, so 4K60 8-bit comes before 4K30 10-bit.
        assertEquals(uhd60, selector.best("0"))
        assertEquals(listOf(uhd60, uhd30Hlg, fhd60, fhd30), selector.heldLadder("0"))
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
        assertEquals(listOf(uhd60, uhd30Hlg, fhd60, fhd30), selector.degradationLadder("0", uhd60Hlg))
    }

    @Test
    fun `flat recording ladder - a held LUT configuration implies its no-LUT version`() {
        // Holds 4K60 10-bit only without the LUT pass.
        val caps = probe(Fixtures.oppo) { r ->
            if (r.config == uhd60Hlg) ProbeMeasurement(SessionStatus.RAN, measuredFps = 57.0, droppedFrames = 40) else Fixtures.oppoTruth(r)
        }
        val selector = CapabilitySelector(caps)
        assertEquals(uhd60, selector.best("0"))
        assertEquals(uhd60Hlg.copy(lut = NONE), selector.best("0", NONE))
        assertTrue(selector.held("0", uhd30Hlg.copy(lut = NONE)))
    }

    @Test
    fun `a selfie sensor narrower than 4K records its largest real size, then 4K upscaled from it`() {
        // D112: the selfie camera lists 3264x1836 at most and hands back another size for 4K; its own app records "4K".
        val selfie = AdvertisedCamera(
            id = "1",
            facing = "FRONT",
            videoModes = listOf(AdvertisedMode(3264, 1836, 30), AdvertisedMode(2560, 1440, 30), AdvertisedMode(1920, 1080, 30)),
            tenBitProfiles = listOf(HLG10),
            recommendedTenBitProfile = HLG10
        )
        val caps = probe(Fixtures.oppo.copy(cameras = listOf(selfie))) { r ->
            when {
                r.checkDeliveredSize -> ProbeMeasurement(SessionStatus.WRONG_SIZE, error = "camera delivers 1920x1440", deliveredSize = "1920x1440")
                r.config.fps == 60 -> ProbeMeasurement(SessionStatus.RAN, measuredFps = 45.0, droppedFrames = 60)
                else -> Fixtures.held(30.0)
            }
        }
        val sensor30 = VideoConfig(3264, 1836, 30, HEVC, HLG10, HARDWARE)
        val upscaled30 = uhd30Hlg.copy(sourceWidth = 3264, sourceHeight = 1836)
        val selector = CapabilitySelector(caps)
        assertEquals(listOf(sensor30, upscaled30, fhd30), selector.heldLadder("1"))
        assertEquals("3840x2160@30 HEVC 10-bit HARDWARE ↑3264x1836", upscaled30.label)
        // The upscaled 4K streams the listed size: no size check, and the refused native 4K implies nothing about it.
        val results = caps.camera("1")!!.results
        assertEquals(ProbeOutcome.WRONG_SIZE, results.first { it.config == uhd30Hlg }.outcome)
        assertTrue(results.single { it.config == upscaled30 }.advertised)
        assertFalse(selector.held("1", uhd30Hlg))
        // Neither covers the other: they stream different sizes.
        assertFalse(upscaled30.covers(uhd30Hlg.copy(lut = NONE)))
        assertFalse(uhd60Hlg.covers(upscaled30))
        assertTrue(upscaled30.covers(upscaled30.copy(dynamicRange = STANDARD)))
    }

    @Test
    fun `only a camera without 4K gets real-size and upscaled rungs`() {
        assertEquals(BaseTargetLadder.rungs, CameraLadder.of(Fixtures.oppo.cameras[0]))
        assertEquals(BaseTargetLadder.rungs, CameraLadder.of(Fixtures.oppo.cameras[1]))
        assertEquals(BaseTargetLadder.rungs, CameraLadder.of(null))
        val modes = listOf(AdvertisedMode(3264, 2448, 30), AdvertisedMode(2560, 1440, 60), AdvertisedMode(3200, 1800, 30))
        val wide = AdvertisedCamera("2", "FRONT", videoModes = modes)
        assertEquals(AdvertisedMode(3200, 1800, 30), CameraLadder.sensorSize(wide))
        val ladder = CameraLadder.of(wide)
        assertEquals(BaseTargetLadder.rungs.size + 8, ladder.size)
        // Between the 4K and the 1080p rungs: the real size, then 4K from it.
        assertEquals(listOf(3840, 3840, 3840, 3840, 3200, 3200, 3200, 3200, 3840, 3840, 3840, 3840, 1920, 1920), ladder.map { it.width })
        assertTrue(ladder.filter { it.sourceWidth != null }.all { it.width == 3840 && it.sourceWidth == 3200 && it.sourceHeight == 1800 })
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
