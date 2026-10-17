package com.qtekfun.ultimatecamera.core.recording

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Regression: an interrupted fusion left `cache/hdr/frame*.i420` (54 MB) behind on the Pixel 8. */
class HdrWorkDirTest {
    private lateinit var root: File
    private lateinit var work: HdrWorkDir

    @Before
    fun setUp() {
        root = Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "hdr-work").toFile()
        work = HdrWorkDir(File(root, "hdr"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun stale(name: String) = File(work.dir.apply { mkdirs() }, name).apply { writeBytes(ByteArray(16)) }

    @Test
    fun clearRemovesFramesLeftByAnInterruptedShot() {
        stale("frame0${HdrWorkDir.FRAME_SUFFIX}")
        stale("frame1${HdrWorkDir.FRAME_SUFFIX}")
        stale("fused.jpg")
        assertEquals(3, work.clear())
        assertTrue(work.dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun clearOnAMissingDirectoryIsHarmless() {
        assertEquals(0, work.clear())
    }

    @Test
    fun aSessionStartsEmptyAndLeavesNothingAfterAFailure() {
        stale("frame2${HdrWorkDir.FRAME_SUFFIX}")
        try {
            work.session { dir ->
                assertTrue(dir.listFiles().isNullOrEmpty())
                File(dir, "frame0${HdrWorkDir.FRAME_SUFFIX}").writeBytes(ByteArray(16))
                throw IOException("encoder failed")
            }
            fail("the failure must propagate")
        } catch (e: IOException) {
            assertEquals("encoder failed", e.message)
        }
        assertTrue(work.dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun aSessionLeavesNothingAfterSuccess() {
        val result = work.session { dir -> File(dir, "fused.jpg").apply { writeBytes(ByteArray(8)) }.length() }
        assertEquals(8L, result)
        assertTrue(work.dir.listFiles().isNullOrEmpty())
    }
}
