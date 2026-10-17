package com.qtekfun.ultimatecamera.feature.camera.background

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TakeMarkerTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "files/${TakeMarker.FILE_NAME}")

    @Test
    fun aCleanTakeLeavesNothingBehind() {
        val marker = TakeMarker(file())
        marker.begin("UC_0001.mp4")
        marker.end()
        assertFalse(file().exists())
        assertNull(TakeMarker(file()).consumeInterrupted())
    }

    @Test
    fun aTakeKilledMidWayIsReportedOnceAtTheNextStart() {
        TakeMarker(file()).begin("UC_0002.mp4")
        // Process death: end() never runs. The next process reads the marker.
        val next = TakeMarker(file())
        assertEquals("UC_0002.mp4", next.consumeInterrupted())
        assertNull(next.consumeInterrupted())
    }

    @Test
    fun aSplitTakeReportsItsLatestPart() {
        val marker = TakeMarker(file())
        marker.begin("UC_0003_T001.mp4")
        marker.begin("UC_0003_T002.mp4")
        assertEquals("UC_0003_T002.mp4", TakeMarker(file()).consumeInterrupted())
    }

    @Test
    fun anEmptyMarkerStillCounts() {
        file().parentFile!!.mkdirs()
        file().writeText("")
        assertEquals(TakeMarker.UNKNOWN, TakeMarker(file()).consumeInterrupted())
    }
}
