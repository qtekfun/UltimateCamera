package com.qtekfun.ultimatecamera.feature.settings

import com.qtekfun.ultimatecamera.core.camera.DiagnosticLog
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSettingsTest {
    @Test
    fun `the shared copy goes to the only cache folder the provider exposes`() {
        val cache = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "cache").toFile()
        val copy = shareCopy(cache, "line\n")
        assertEquals(File(File(cache, SHARE_DIR), SHARE_FILE), copy)
        assertEquals("line\n", copy.readText())
        assertEquals("other\n", shareCopy(cache, "other\n").readText())
    }

    @Test
    fun `the switch turns the log on and off and delete empties it`() {
        val dir = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "diag").toFile()
        DiagnosticLog.init(dir)
        toggleLog(true)
        assertTrue(DiagnosticLog.enabled)
        DiagnosticLog.append(dir, listOf("line\n"))
        clearLog()
        assertFalse(DiagnosticLog.text().contains("line"))
        toggleLog(false)
        assertFalse(DiagnosticLog.enabled)
    }
}
