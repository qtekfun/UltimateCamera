package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.codec.VideoContainer
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeFinishingTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    // 2026-10-17 12:10:05.5 UTC = 14:10:05.5 in Madrid (summer time).
    private val start = 1_792_239_005_500_000_000L

    @Test
    fun aPartGetsItsTimeOfDayTimecodeAndCreationDate() {
        val opts = TakeFinishing.options(start, fps = 30, timecodeTrack = true, container = VideoContainer.MP4, zone = madrid)
        assertEquals("14:10:05:15", opts.timecode?.label)
        assertEquals(1_792_239_005L, opts.creationEpochSeconds)
        assertFalse(opts.quickTime)
    }

    @Test
    fun eachSplitPartStartsAtItsOwnFirstFrame() {
        // A FAT32 part that starts 20 minutes and 2 frames later.
        val later = start + 20 * 60 * 1_000_000_000L + 2 * 1_000_000_000L / 25
        val opts = TakeFinishing.options(later, fps = 25, timecodeTrack = true, container = VideoContainer.MP4, zone = madrid)
        assertEquals("14:30:05:14", opts.timecode?.label)
    }

    @Test
    fun movIsConvertedEvenWithoutATimecode() {
        val opts = TakeFinishing.options(null, fps = 30, timecodeTrack = true, container = VideoContainer.MOV, zone = madrid)
        assertNull(opts.timecode)
        assertTrue(opts.quickTime)
        assertFalse(opts.isEmpty)
    }

    @Test
    fun nothingToDoForAnMp4WithTheTrackOff() {
        assertTrue(TakeFinishing.options(start, fps = 30, timecodeTrack = false, container = VideoContainer.MP4, zone = madrid).isEmpty)
    }
}
