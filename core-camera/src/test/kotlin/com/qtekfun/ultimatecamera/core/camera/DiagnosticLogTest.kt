package com.qtekfun.ultimatecamera.core.camera

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    private fun tempDir(): File = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "diag").toFile()

    @Test
    fun `a line is time, tag and one-line message`() {
        val line = DiagnosticLog.line(0L, "camera", "open 0\n3840x2160")
        assertTrue(line, Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} camera open 0 3840x2160\n""").matches(line))
    }

    @Test
    fun `the log rolls over to one older file past its size`() {
        val dir = tempDir()
        DiagnosticLog.append(dir, listOf("a\n", "b\n"), maxBytes = 4)
        assertEquals("a\nb\n", File(dir, DiagnosticLog.FILE_NAME).readText())
        DiagnosticLog.append(dir, listOf("c\n"), maxBytes = 4)
        assertEquals("a\nb\n", File(dir, DiagnosticLog.OLD_FILE_NAME).readText())
        assertEquals("c\n", File(dir, DiagnosticLog.FILE_NAME).readText())
        DiagnosticLog.append(dir, emptyList(), maxBytes = 4)
        assertEquals("c\n", File(dir, DiagnosticLog.FILE_NAME).readText())
    }

    @Test
    fun `off by default, remembered when turned on, read oldest first and cleared`() {
        val dir = tempDir()
        DiagnosticLog.init(dir)
        assertFalse(DiagnosticLog.enabled)
        var built = false
        DiagnosticLog.log("t") {
            built = true
            "x"
        }
        assertFalse("a message is not even built while the log is off", built)
        DiagnosticLog.setEnabled(true)
        // The background writer adds the "on" line; wait for it so nothing is written behind the checks below.
        val until = System.currentTimeMillis() + WAIT_MS
        while (!DiagnosticLog.text().contains(" log on") && System.currentTimeMillis() < until) Thread.sleep(POLL_MS)
        assertTrue(DiagnosticLog.text().contains(" log on"))
        DiagnosticLog.init(dir)
        assertTrue(DiagnosticLog.enabled)
        DiagnosticLog.clear()
        DiagnosticLog.append(dir, listOf("old\n"), maxBytes = 1)
        DiagnosticLog.append(dir, listOf("new\n"), maxBytes = 1)
        assertEquals("old\nnew\n", DiagnosticLog.text())
        assertTrue(DiagnosticLog.size() > 0)
        DiagnosticLog.clear()
        assertEquals(0L, DiagnosticLog.size())
        DiagnosticLog.setEnabled(false)
        DiagnosticLog.init(dir)
        assertFalse(DiagnosticLog.enabled)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
