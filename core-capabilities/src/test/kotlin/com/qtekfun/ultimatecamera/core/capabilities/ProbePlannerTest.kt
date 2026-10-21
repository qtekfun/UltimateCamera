package com.qtekfun.ultimatecamera.core.capabilities

import com.qtekfun.ultimatecamera.core.capabilities.CodecType.H264
import com.qtekfun.ultimatecamera.core.capabilities.CodecType.HEVC
import com.qtekfun.ultimatecamera.core.capabilities.Fixtures.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbePlannerTest {
    private val planner = ProbePlanner()

    @Test
    fun `seed parses the Pixel 8 as advertised`() {
        val seed = Fixtures.pixel8
        assertEquals("Pixel 8", seed.device.identity.model)
        assertEquals(37, seed.device.identity.sdk)
        val main = seed.device.camera("0")!!
        assertEquals(AdvertisedMode(3840, 2160, 60), main.mode(3840, 2160))
        assertEquals(DynamicRange.HLG10, main.tenBitProfile)
        assertTrue(seed.rejectedLabels.contains(label(3840, 2160, 60, HEVC, tenBit = true)))
        assertTrue(seed.heldLabels.contains(label(3840, 2160, 60, HEVC, tenBit = false)))
    }

    @Test
    fun `plan is the ladder cheapest first with no-LUT fallbacks after each 4K rung`() {
        val plan = planner.plan(Fixtures.pixel8.device)
        assertEquals(
            listOf(
                label(1920, 1080, 30, HEVC, false),
                label(1920, 1080, 60, HEVC, false),
                label(3840, 2160, 30, HEVC, true),
                label(3840, 2160, 30, HEVC, true, LutMode.NONE),
                label(3840, 2160, 60, HEVC, false),
                label(3840, 2160, 60, HEVC, false, LutMode.NONE),
                label(3840, 2160, 60, HEVC, true),
                label(3840, 2160, 60, HEVC, true, LutMode.NONE)
            ),
            plan.map { it.config.label }
        )
        assertTrue(plan.all { it.cameraId == "0" && it.preset == null })
        // The Pixel 8 advertises 4K60 and the HEVC encoder claims it: the 10-bit refusal is only found by probing.
        assertTrue(plan.single { it.config.label == label(3840, 2160, 60, HEVC, true) }.advertised)
    }

    @Test
    fun `rates above the advertised maximum are planned as unadvertised attempts`() {
        val plan = planner.plan(Fixtures.oppo)
        val fourK60 = plan.filter { it.config.width == 3840 && it.config.fps == 60 }
        assertEquals(4, fourK60.size)
        assertTrue(fourK60.all { !it.advertised && it.preset == null })
        assertTrue(plan.single { it.config.label == label(3840, 2160, 30, HEVC, true) }.advertised)
    }

    @Test
    fun `unadvertised rates are skipped when the policy says so`() {
        val plan = ProbePlanner(ProbePolicy(tryUnadvertisedFps = false)).plan(Fixtures.oppo)
        assertTrue(plan.filter { it.config.fps == 60 && it.config.width == 3840 }.all { it.preset == ProbeOutcome.NOT_ADVERTISED_SKIPPED })
    }

    @Test
    fun `unlisted sizes and missing 10-bit profiles are skipped without opening the camera`() {
        val plan = planner.plan(Fixtures.oppo, listOf("1"))
        val byLabel = plan.associateBy { it.config.label }
        assertEquals(ProbeOutcome.NOT_ADVERTISED_SKIPPED, byLabel.getValue(label(3840, 2160, 30, HEVC, true)).preset)
        assertEquals(ProbeOutcome.NOT_ADVERTISED_SKIPPED, byLabel.getValue(label(3840, 2160, 60, HEVC, false)).preset)
        val noTenBit = Fixtures.oppo.copy(cameras = listOf(Fixtures.oppo.cameras[0].copy(tenBitProfiles = emptyList(), recommendedTenBitProfile = null)))
        val tenBit = planner.plan(noTenBit).filter { it.config.tenBit }
        assertTrue(tenBit.isNotEmpty() && tenBit.all { it.preset == ProbeOutcome.NOT_ADVERTISED_SKIPPED && it.reason!!.contains("10-bit") })
        val p1080p60 = byLabel.getValue(label(1920, 1080, 60, HEVC, false))
        assertNull("listed size, faster than advertised: tried", p1080p60.preset)
        assertFalse(p1080p60.advertised)
        assertNull(byLabel.getValue(label(1920, 1080, 30, HEVC, false)).preset)
    }

    @Test
    fun `codecs follow the hardware encoders`() {
        val noHevc = Fixtures.oppo.copy(encoders = listOf(AdvertisedEncoder("avc", H264, tenBit = false)))
        val plan = planner.plan(noHevc)
        assertTrue(plan.filter { !it.config.tenBit }.all { it.config.codec == H264 && it.preset == null })
        assertTrue(plan.filter { it.config.tenBit }.all { it.preset == ProbeOutcome.ENCODER_UNSUPPORTED })
        val av1Only = Fixtures.oppo.copy(encoders = listOf(AdvertisedEncoder("av1", CodecType.AV1, tenBit = true), AdvertisedEncoder("avc", H264, false)))
        assertTrue(planner.plan(av1Only).filter { it.config.tenBit }.all { it.config.codec == CodecType.AV1 })
    }

    @Test
    fun `an encoder that denies the rate makes the probe unadvertised but still planned`() {
        val pixel = Fixtures.pixel8.device
        val p = planner.plan(pixel).single { it.config.label == label(1920, 1080, 60, HEVC, false) }
        assertTrue(p.advertised)
        val denying = pixel.copy(encoders = listOf(AdvertisedEncoder("hevc", HEVC, true, mapOf("1920x1080@60" to false))))
        val q = planner.plan(denying).single { it.config.label == label(1920, 1080, 60, HEVC, false) }
        assertFalse(q.advertised)
        assertNull(q.preset)
    }

    @Test
    fun `the primary camera is the first back camera, and auto-configuration adds the first front camera`() {
        assertEquals(listOf("0"), ProbePlanner.primaryCameraIds(Fixtures.oppo))
        assertEquals(listOf("0", "1"), ProbePlanner.autoConfigCameraIds(Fixtures.oppo))
        val frontOnly = Fixtures.oppo.copy(cameras = Fixtures.oppo.cameras.filter { it.facing == "FRONT" })
        assertEquals(listOf("1"), ProbePlanner.primaryCameraIds(frontOnly))
        assertEquals(listOf("1"), ProbePlanner.autoConfigCameraIds(frontOnly))
        val backOnly = Fixtures.oppo.copy(cameras = Fixtures.oppo.cameras.filter { it.facing == "BACK" })
        assertEquals(listOf("0"), ProbePlanner.autoConfigCameraIds(backOnly))
    }

    @Test
    fun `the front camera's plain ladder comes before the main camera's variants, which it never gets`() {
        val plan = planner.plan(Fixtures.oppo, ProbePlanner.autoConfigCameraIds(Fixtures.oppo), direct = true) { listOf("vendor-x") }
        val front = plan.indices.filter { plan[it].cameraId == "1" }
        val variants = plan.indices.filter { plan[it].config.vendorMode != null }
        assertTrue(front.isNotEmpty())
        assertTrue(variants.isNotEmpty())
        assertTrue(front.max() < variants.min())
        assertTrue(variants.all { plan[it].cameraId == "0" })
        // The main camera's plain ladder comes first.
        assertTrue(plan.indices.filter { plan[it].cameraId == "0" && plan[it].config.vendorMode == null }.max() < front.min())
    }
}
