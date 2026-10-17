package com.qtekfun.ultimatecamera.core.storage.crash

import android.app.ApplicationExitInfo
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    private val env = CrashEnvironment("0.6.3-fase6", "OPPO", "CPH2841", 36)
    private val dir: File = Files.createTempDirectory(File("build/tmp/crashlog-test").apply { mkdirs() }.toPath(), "t").toFile()

    @Test
    fun reportHasHeaderAndFullTraceWithCause() {
        val log = CrashLog(dir)
        val error = IllegalStateException("outer", java.io.IOException("inner cause"))
        val file = log.write(env, Thread("NsdManager"), error, 1_760_000_000_000L)
        assertNotNull(file)
        val text = file!!.readText()
        assertTrue(text, text.startsWith("UltimateCamera crash report\n"))
        assertTrue(text.contains("time: 2025-10-09T08:53:20Z"))
        assertTrue(text.contains("app: 0.6.3-fase6"))
        assertTrue(text.contains("device: OPPO CPH2841"))
        assertTrue(text.contains("android: SDK 36"))
        assertTrue(text.contains("thread: NsdManager"))
        assertTrue(text.contains("java.lang.IllegalStateException: outer"))
        assertTrue(text.contains("Caused by: java.io.IOException: inner cause"))
        assertEquals(text, log.latest())
    }

    @Test
    fun keepsOnlyTheNewestReports() {
        val log = CrashLog(dir, maxReports = 5)
        // Two in the same millisecond too: the sequence keeps both and their order.
        listOf(100L, 200L, 300L, 300L, 400L, 500L, 600L).forEachIndexed { i, t -> log.write(env, Thread("t$i"), RuntimeException("crash $i"), t) }
        val reports = log.reports()
        assertEquals(5, reports.size)
        assertTrue(log.latest()!!.contains("crash 6"))
        val texts = reports.map { it.readText() }
        assertTrue(texts.last().contains("crash 2"))
        assertTrue(texts[texts.size - 2].contains("crash 3"))
        assertFalse(texts.any { it.contains("crash 0") || it.contains("crash 1") })
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aHugeTraceIsCutToTheLimitAndMarked() {
        val log = CrashLog(dir, maxBytes = 4_096)
        // Multi-byte characters so the cut can fall inside a UTF-8 sequence.
        val file = log.write(env, Thread("main"), RuntimeException("ñ€".repeat(50_000)), 1L)!!
        assertTrue(file.length() <= 4_096)
        val text = file.readText()
        assertTrue(text.endsWith(CrashLog.TRUNCATED))
        assertTrue(text.startsWith("UltimateCamera crash report"))
    }

    @Test
    fun anUnwritableFolderGivesNullInsteadOfThrowing() {
        val notADir = File(dir, "file").apply { writeText("x") }
        assertNull(CrashLog(notADir).write(env, Thread("main"), RuntimeException(), 1L))
        assertNull(CrashLog(notADir).latest())
    }

    @Test
    fun clearRemovesEveryReport() {
        val log = CrashLog(dir)
        log.write(env, Thread("main"), RuntimeException(), 1L)
        log.clear()
        assertTrue(log.reports().isEmpty())
        assertNull(log.latest())
    }

    @Test
    fun recorderWritesThenChainsToThePreviousHandler() {
        val seen = mutableListOf<Pair<Thread, Throwable>>()
        val previous = Thread.UncaughtExceptionHandler { t, e -> seen += t to e }
        val recorder = CrashRecorder(CrashLog(dir), env, previous) { 42L }
        val thread = Thread("worker")
        val error = IllegalArgumentException("listener already in use")
        recorder.uncaughtException(thread, error)
        assertEquals(1, seen.size)
        assertSame(thread, seen[0].first)
        assertSame(error, seen[0].second)
        assertTrue(CrashLog(dir).latest()!!.contains("listener already in use"))
    }

    @Test
    fun recorderStillChainsWhenRecordingFails() {
        val seen = mutableListOf<Throwable>()
        val notADir = File(dir, "file").apply { writeText("x") }
        val recorder = CrashRecorder(CrashLog(notADir), env, { _, e -> seen += e }) { error("clock broke") }
        val error = RuntimeException("boom")
        recorder.uncaughtException(Thread.currentThread(), error)
        assertEquals(listOf<Throwable>(error), seen)
    }

    @Test
    fun installPutsTheRecorderInFrontOnceAndKeepsThePreviousHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val seen = mutableListOf<Throwable>()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
            val first = CrashRecorder.install(CrashLog(dir), env)
            assertSame(first, Thread.getDefaultUncaughtExceptionHandler())
            assertSame(first, CrashRecorder.install(CrashLog(dir), env))
            val error = RuntimeException("chained")
            first.uncaughtException(Thread.currentThread(), error)
            assertEquals(listOf<Throwable>(error), seen)
            assertEquals(1, CrashLog(dir).reports().size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun composeJoinsTheLastCrashAndTheExits() {
        assertNull(CrashReports.compose(env, null, emptyList()))
        val text = CrashReports.compose(env, "trace here\n", listOf(ProcessExit(1L, "native crash", "abort: JNI DETECTED ERROR")))!!
        assertTrue(text.startsWith("UltimateCamera 0.6.3-fase6 - OPPO CPH2841 - SDK 36"))
        assertTrue(text.indexOf("trace here") < text.indexOf("abort: JNI DETECTED ERROR"))
        val onlyExits = CrashReports.compose(env, null, listOf(ProcessExit(1L, "anr", "ANR")))!!
        assertTrue(onlyExits.contains("(none recorded by the app)"))
    }

    @Test
    fun exitRecordsAreDescribedPerReason() {
        val java = CrashReports.describe(ApplicationExitInfo.REASON_CRASH, 0L, 1, "java.lang.IllegalStateException: x", null)
        assertEquals("java crash", java.reason)
        assertTrue(java.text, java.text.startsWith("1970-01-01T00:00:00Z  java crash  status=1\n  java.lang.IllegalStateException: x"))
        val anr = CrashReports.describe(ApplicationExitInfo.REASON_ANR, 0L, 0, "  ", null)
        assertEquals("not responding (ANR)", anr.reason)
        assertFalse(anr.text.contains("\n"))
        assertEquals("reason 99", CrashReports.describe(99, 0L, 0, null, null).reason)
        // A native crash with an unreadable tombstone still gives its line; a readable one adds the summary.
        val unreadable = CrashReports.describe(ApplicationExitInfo.REASON_CRASH_NATIVE, 0L, 6, null, byteArrayOf(0x0b))
        assertEquals("1970-01-01T00:00:00Z  native crash  status=6", unreadable.text)
        val tombstone = byteArrayOf(0x72, 0x05) + "boom!".toByteArray() // field 14 (abort message), length 5
        assertTrue(CrashReports.describe(ApplicationExitInfo.REASON_CRASH_NATIVE, 0L, 6, "crash", tombstone).text.endsWith("abort: boom!\n"))
        assertEquals("0.7.0", CrashReports.environment("0.7.0").appVersion)
    }
}
