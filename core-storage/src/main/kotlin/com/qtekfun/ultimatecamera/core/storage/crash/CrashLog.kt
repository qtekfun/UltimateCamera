package com.qtekfun.ultimatecamera.core.storage.crash

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** What a crash report says about the build and the phone. No personal data: no device name, account or location. */
data class CrashEnvironment(val appVersion: String, val manufacturer: String, val model: String, val sdk: Int)

/**
 * The last few uncaught exceptions, one small text file each in an app-private folder (e.g. `filesDir/crashes`), so the
 * owner can share them from Diagnostics without adb (D77). Keeps the newest [maxReports]; each file is at most
 * [maxBytes] (a longer trace is cut and marked). File names sort chronologically. Never throws from [write].
 */
class CrashLog(private val dir: File, private val maxReports: Int = MAX_REPORTS, private val maxBytes: Int = MAX_REPORT_BYTES) {
    init {
        require(maxReports >= 1 && maxBytes >= MIN_REPORT_BYTES)
    }

    private var sequence = 0

    /** Writes one report and drops the oldest beyond [maxReports]. Returns the file, or null when the disk refused. */
    @Suppress("TooGenericExceptionCaught") // Crash path: nothing may escape, not even an OutOfMemoryError while formatting.
    fun write(environment: CrashEnvironment, thread: Thread, error: Throwable, nowMs: Long): File? = try {
        val text = bounded(format(environment, thread, error, nowMs))
        if (!dir.isDirectory) dir.mkdirs()
        val name = synchronized(this) { "$PREFIX${nowMs.toString().padStart(TIME_DIGITS, '0')}-${sequence++}$SUFFIX" }
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(text)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            null
        } else {
            prune()
            target
        }
    } catch (_: Throwable) {
        null
    }

    /** Report files, newest first. */
    fun reports(): List<File> = dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
        .orEmpty()
        .sortedWith(compareByDescending<File> { timeOf(it.name) }.thenByDescending { sequenceOf(it.name) })

    /** Text of the newest report, or null when none was recorded. */
    fun latest(): String? = reports().firstOrNull()?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }

    /** Removes every report (the owner sent them). */
    fun clear() {
        dir.listFiles { f -> f.name.startsWith(PREFIX) }.orEmpty().forEach { it.delete() }
    }

    private fun prune() {
        reports().drop(maxReports).forEach { it.delete() }
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".tmp") }.orEmpty().forEach { it.delete() }
    }

    private fun bounded(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        val marker = TRUNCATED.toByteArray(Charsets.UTF_8)
        // Room for a partial UTF-8 sequence at the cut turning into one 3-byte replacement character.
        val keep = maxBytes - marker.size - UTF8_SLACK
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val head = decoder.decode(ByteBuffer.wrap(bytes, 0, keep)).toString().toByteArray(Charsets.UTF_8)
        return head + marker
    }

    companion object {
        const val MAX_REPORTS = 5
        const val MAX_REPORT_BYTES = 64 * 1024
        const val DIR_NAME = "crashes"
        const val TRUNCATED = "\n[... truncated]\n"
        private const val MIN_REPORT_BYTES = 256
        private const val UTF8_SLACK = 4
        private const val PREFIX = "crash-"
        private const val SUFFIX = ".txt"
        private const val TIME_DIGITS = 13

        private fun timeOf(name: String): Long = name.removePrefix(PREFIX).substringBefore('-').toLongOrNull() ?: 0L

        private fun sequenceOf(name: String): Int = name.removePrefix(PREFIX).substringAfter('-').removeSuffix(SUFFIX).toIntOrNull() ?: 0

        /** The report text: a short header (when, which build, which phone, which thread) and the full stack trace. */
        fun format(environment: CrashEnvironment, thread: Thread, error: Throwable, nowMs: Long): String {
            val trace = StringWriter().also { w -> PrintWriter(w).use { error.printStackTrace(it) } }.toString()
            return buildString {
                append("UltimateCamera crash report\n")
                append("time: ").append(Instant.ofEpochMilli(nowMs)).append('\n')
                append("app: ").append(environment.appVersion).append('\n')
                append("device: ").append(environment.manufacturer).append(' ').append(environment.model).append('\n')
                append("android: SDK ").append(environment.sdk).append('\n')
                append("thread: ").append(thread.name).append('\n')
                append('\n')
                append(trace)
            }
        }
    }
}

/**
 * Default uncaught-exception handler: records the crash in [log], then always hands it to [previous] (on Android the
 * platform handler, which shows the dialog and kills the process), even when recording failed.
 */
class CrashRecorder(
    private val log: CrashLog,
    private val environment: CrashEnvironment,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val nowMs: () -> Long = System::currentTimeMillis
) : Thread.UncaughtExceptionHandler {
    @Suppress("TooGenericExceptionCaught")
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            log.write(environment, thread, error, nowMs())
        } catch (_: Throwable) {
            // Never let the recorder hide the original crash.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        /** Installs a recorder in front of the current default handler; a second call keeps the first recorder. */
        fun install(log: CrashLog, environment: CrashEnvironment): CrashRecorder {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is CrashRecorder) return current
            return CrashRecorder(log, environment, current).also { Thread.setDefaultUncaughtExceptionHandler(it) }
        }
    }
}
