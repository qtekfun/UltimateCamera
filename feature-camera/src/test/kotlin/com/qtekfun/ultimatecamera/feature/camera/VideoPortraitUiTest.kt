package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.gpu.portrait.VideoBlurLevel
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitBlock
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitNotice
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitOptions
import com.qtekfun.ultimatecamera.core.recording.VideoPortraitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The video Blur chip's decisions (D89). */
class VideoPortraitUiTest {
    private val running =
        VideoPortraitStatus(offered = true, on = true, source = DepthSource.DEPTH_CAMERA, start = VideoBlurLevel.HIGH, level = VideoBlurLevel.HIGH)

    @Test
    fun offByDefaultAtTheSameApertureAsThePhoto() {
        val ui = VideoPortraitUi()
        assertFalse(ui.on)
        assertEquals(2.8f, ui.fNumber, 0f)
        assertEquals(0f, ui.copy(aperture = 0).fNumber, 0f)
    }

    @Test
    fun theChipShowsOnlyWhenOffered() {
        assertFalse(VideoPortraitUi.chipShown(VideoPortraitStatus()))
        assertTrue(VideoPortraitUi.chipShown(VideoPortraitStatus(offered = true)))
    }

    @Test
    fun theLabelSaysOffOnLighterOrNotAvailable() {
        assertEquals(VideoPortraitUi.ChipLabel.OFF, VideoPortraitUi().label(running))
        val on = VideoPortraitUi(on = true)
        assertEquals(VideoPortraitUi.ChipLabel.ON, on.label(running))
        assertEquals(VideoPortraitUi.ChipLabel.LOWERED, on.label(running.copy(level = VideoBlurLevel.LOW)))
        assertEquals(
            VideoPortraitUi.ChipLabel.UNAVAILABLE,
            on.label(running.copy(source = null, level = VideoBlurLevel.OFF, block = VideoPortraitBlock.TOO_SLOW))
        )
        assertEquals(VideoPortraitUi.ChipLabel.UNAVAILABLE, on.label(VideoPortraitStatus(offered = true, on = true, block = VideoPortraitBlock.TEN_BIT)))
    }

    @Test
    fun onlyOnAndOffReopenTheCamera() {
        val ui = VideoPortraitUi()
        assertTrue(ui.reopens(ui.copy(on = true)))
        assertFalse(ui.reopens(ui.copy(aperture = 5)))
    }

    @Test
    fun everyReasonHasItsOwnText() {
        val texts = VideoPortraitBlock.entries.map(VideoPortraitUi::blockText)
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun noticesNameTheReasonWhenTheBlurWentOff() {
        val (lowered, none) = VideoPortraitUi.noticeText(VideoPortraitNotice.Lowered(VideoBlurLevel.LOW))
        assertEquals(R.string.video_portrait_notice_lowered, lowered)
        assertNull(none)
        val (off, reason) = VideoPortraitUi.noticeText(VideoPortraitNotice.TurnedOff(VideoPortraitBlock.TOO_SLOW))
        assertEquals(R.string.video_portrait_notice_off, off)
        assertEquals(R.string.video_portrait_block_too_slow, reason)
    }

    @Test
    fun theSettingsReachTheOptionsAndKeepTheChip() {
        val current = VideoPortraitOptions(on = true)
        assertEquals(
            VideoPortraitOptions(enabled = true, experimental = true, on = true),
            VideoPortraitControl.options(current, enabled = true, experimental = true)
        )
    }
}
