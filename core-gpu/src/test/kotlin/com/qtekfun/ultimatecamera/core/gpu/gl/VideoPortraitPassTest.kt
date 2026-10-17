package com.qtekfun.ultimatecamera.core.gpu.gl

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurCause
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurGovernor
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurLevel
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoPortraitPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions of the recording's blur pass (D89) with a fake GL stage: when it draws, steps down, fails, stops. */
class VideoPortraitPassTest {
    private class FakeStage(val plan: VideoPortraitPlan, private val failOn: String?) : VideoPortraitStageOps {
        val calls = ArrayList<String>()
        var released = false

        private fun call(name: String) {
            calls += name
            if (name == failOn) throw GlException("$name failed")
        }

        override fun uploadDepth(update: LiveDepth) = call("upload")

        override fun prepare(cameraTexture: Int, texMatrix: FloatArray, d: LiveDepth) = call("prepare")

        override fun composite(cameraTexture: Int, texMatrix: FloatArray, width: Int, height: Int, d: LiveDepth) = call("composite")

        override fun release() {
            released = true
        }
    }

    private val stages = ArrayList<FakeStage>()
    private var failOn: String? = null
    private var failCreate = false
    private val changes = ArrayList<Pair<VideoBlurLevel, VideoBlurCause>>()
    private val pass = VideoPortraitPass(1920, 1080) { plan ->
        if (failCreate) throw GlException("no targets")
        FakeStage(plan, failOn).also { stages += it }
    }.also { it.onLevel = { level, cause -> changes += level to cause } }
    private val matrix = FloatArray(16)
    private val frame30 = 1_000_000_000L / 30
    private val depth = LiveDepth(Plane(4, 3), focus = 0.5f, fNumber = 2.8f)

    /** One encoded frame: true when the blur was blended. */
    private fun frame(renderNanos: Long = frame30 / 4, drops: Int = 0): Boolean {
        val blur = pass.beforeEncoder(1, matrix)
        if (blur) pass.afterEncoder(1, matrix, 1920, 1080)
        pass.frameDone(renderNanos, drops)
        return blur
    }

    @Test
    fun offUntilConfiguredAndUntilTheFirstDepthArrives() {
        assertFalse(frame())
        assertEquals(VideoBlurLevel.OFF, pass.level)
        pass.configure(VideoBlurLevel.HIGH, 30)
        assertEquals(VideoBlurLevel.HIGH, pass.level)
        assertFalse(frame())
        assertEquals(960, stages.single().plan.width)
        pass.setDepth(depth)
        assertTrue(frame())
        assertEquals(listOf("upload", "prepare", "composite"), stages.single().calls)
        // The depth is uploaded once, not every frame.
        assertTrue(frame())
        assertEquals(listOf("upload", "prepare", "composite", "prepare", "composite"), stages.single().calls)
    }

    @Test
    fun slowFramesStepDownToALighterStageAndReportIt() {
        pass.configure(VideoBlurLevel.HIGH, 30)
        pass.setDepth(depth)
        repeat(VideoBlurGovernor.WARMUP + VideoBlurGovernor.SLOW_LIMIT + 1) { frame(renderNanos = frame30) }
        assertEquals(VideoBlurLevel.MEDIUM, pass.level)
        assertEquals(listOf(VideoBlurLevel.MEDIUM to VideoBlurCause.SLOW_FRAMES), changes)
        assertTrue(stages[0].released)
        assertEquals(640, stages[1].plan.width)
        // The new stage gets the depth it needs at once.
        assertEquals("upload", stages[1].calls.first())
        assertTrue(frame())
    }

    @Test
    fun droppedFramesStepDownUntilOffAndThenNothingIsDrawn() {
        pass.configure(VideoBlurLevel.LOW, 30)
        pass.setDepth(depth)
        repeat(VideoBlurGovernor.WARMUP) { frame() }
        frame(drops = 1)
        assertEquals(VideoBlurLevel.OFF, pass.level)
        assertEquals(listOf(VideoBlurLevel.OFF to VideoBlurCause.DROPPED_FRAMES), changes)
        assertFalse(frame())
        assertTrue(stages.single().released)
    }

    @Test
    fun framesThatDidNotBlurAreNotJudged() {
        pass.configure(VideoBlurLevel.HIGH, 30)
        // No depth yet: slow frames and drops are the camera's, not the blur's.
        repeat(100) { frame(renderNanos = frame30 * 2, drops = 3) }
        assertEquals(VideoBlurLevel.HIGH, pass.level)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun aFailingPassTurnsTheBlurOffOnceAndTheTakeGoesOn() {
        failOn = "composite"
        pass.configure(VideoBlurLevel.HIGH, 30)
        pass.setDepth(depth)
        assertTrue(frame())
        assertEquals(VideoBlurLevel.OFF, pass.level)
        assertNotNull(pass.problem)
        assertEquals(listOf(VideoBlurLevel.OFF to VideoBlurCause.GPU_FAILURE), changes)
        assertFalse(frame())
        assertEquals(1, changes.size)
    }

    @Test
    fun aFailingPrepareOrUploadSkipsTheBlendAndTurnsItOff() {
        failOn = "upload"
        pass.configure(VideoBlurLevel.MEDIUM, 30)
        pass.setDepth(depth)
        assertFalse(frame())
        assertEquals(VideoBlurLevel.OFF, pass.level)
        assertEquals(listOf(VideoBlurLevel.OFF to VideoBlurCause.GPU_FAILURE), changes)
    }

    @Test
    fun aStageThatCannotBeMadeIsOffAndReportedOnce() {
        failCreate = true
        pass.configure(VideoBlurLevel.HIGH, 30)
        pass.setDepth(depth)
        assertFalse(frame())
        assertEquals(VideoBlurLevel.OFF, pass.level)
        assertEquals(listOf(VideoBlurLevel.OFF to VideoBlurCause.GPU_FAILURE), changes)
    }

    @Test
    fun aFailedStepDownIsReportedOnceAsAFailure() {
        pass.configure(VideoBlurLevel.HIGH, 30)
        pass.setDepth(depth)
        repeat(VideoBlurGovernor.WARMUP) { frame() }
        failCreate = true
        frame(drops = 1)
        assertEquals(VideoBlurLevel.OFF, pass.level)
        assertEquals(listOf(VideoBlurLevel.OFF to VideoBlurCause.GPU_FAILURE), changes)
    }

    @Test
    fun configuringOffOrReleasingDropsTheStageAndANewConfigurationStartsClean() {
        failOn = "composite"
        pass.configure(VideoBlurLevel.HIGH, 30)
        pass.setDepth(depth)
        frame()
        assertNotNull(pass.problem)
        failOn = null
        pass.configure(VideoBlurLevel.LOW, 30)
        assertTrue(frame())
        assertNull(pass.problem)
        pass.configure(null, 30)
        assertEquals(VideoBlurLevel.OFF, pass.level)
        assertFalse(frame())
        assertTrue(stages.all { it.released })
        pass.configure(VideoBlurLevel.LOW, 30)
        pass.release()
        assertEquals(VideoBlurLevel.OFF, pass.level)
    }

    @Test
    fun aNewTakeGivesTheGovernorAFreshWarmUp() {
        pass.configure(VideoBlurLevel.HIGH, 30)
        pass.setDepth(depth)
        repeat(VideoBlurGovernor.WARMUP) { frame() }
        pass.takeStarted()
        repeat(VideoBlurGovernor.WARMUP) { frame(drops = 1) }
        assertEquals(VideoBlurLevel.HIGH, pass.level)
    }
}
