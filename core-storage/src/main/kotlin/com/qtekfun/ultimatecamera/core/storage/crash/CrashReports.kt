package com.qtekfun.ultimatecamera.core.storage.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant

/** One abnormal end of the app's process as the system recorded it (ApplicationExitInfo), already formatted. */
data class ProcessExit(val timeMs: Long, val reason: String, val text: String)

/**
 * Everything the owner can send us after a crash (D77): the newest Java crash recorded by [CrashRecorder] and the
 * system's record of the last abnormal process exits, which also covers native crashes (with the tombstone summary)
 * and ANRs that no Java handler sees. Android glue around the pure [CrashLog], [TombstoneSummary] and [compose]; verified
 * by hand (docs/TESTING.md).
 */
class CrashReports(private val context: Context, private val appVersion: String) {
    val log = CrashLog(File(context.applicationContext.filesDir, CrashLog.DIR_NAME))

    /** The text to share; null when nothing was recorded. Reads files: call it off the main thread. */
    fun shareText(): String? = compose(environment(appVersion), log.latest(), recentExits())

    /** Forgets the recorded Java crashes (the system's exit history cannot be cleared). */
    fun clear() = log.clear()

    @Suppress("TooGenericExceptionCaught") // Vendor ActivityManager quirks must not break the diagnostics screen.
    fun recentExits(max: Int = MAX_EXITS): List<ProcessExit> = try {
        val am = context.getSystemService(ActivityManager::class.java)
        am.getHistoricalProcessExitReasons(null, 0, HISTORY)
            .filter { it.reason in REPORTED }
            .take(max)
            .map(::describe)
    } catch (_: Exception) {
        emptyList()
    }

    private fun describe(info: ApplicationExitInfo): ProcessExit {
        val trace = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            runCatching { info.traceInputStream?.use { it.readNBytes(MAX_TOMBSTONE_BYTES) } }.getOrNull()
        } else {
            null
        }
        return describe(info.reason, info.timestamp, info.status, info.description, trace)
    }

    companion object {
        private const val MAX_EXITS = 5
        private const val HISTORY = 16
        private const val MAX_TOMBSTONE_BYTES = 4 * 1024 * 1024
        private const val MAX_DESCRIPTION_CHARS = 2_000
        private val REPORTED = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)

        /** One system exit record as text; [tombstone] is the native crash's trace (protobuf), when there is one. */
        fun describe(reasonCode: Int, timestampMs: Long, status: Int, description: String?, tombstone: ByteArray?): ProcessExit {
            val reason = when (reasonCode) {
                ApplicationExitInfo.REASON_CRASH -> "java crash"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                else -> "reason $reasonCode"
            }
            val text = buildString {
                append(Instant.ofEpochMilli(timestampMs)).append("  ").append(reason)
                append("  status=").append(status)
                description?.takeIf { it.isNotBlank() }?.let { append("\n  ").append(it.take(MAX_DESCRIPTION_CHARS)) }
                tombstone?.let { TombstoneSummary.parse(it)?.format() }?.let { append('\n').append(it) }
            }
            return ProcessExit(timestampMs, reason, text)
        }

        /** This build and phone (no personal data). */
        fun environment(appVersion: String): CrashEnvironment = CrashEnvironment(
            appVersion = appVersion,
            manufacturer = Build.MANUFACTURER ?: "?",
            model = Build.MODEL ?: "?",
            sdk = Build.VERSION.SDK_INT
        )

        /** The shared text: newest Java crash first, then the system's exit records. Null when both are empty. */
        fun compose(environment: CrashEnvironment, latestCrash: String?, exits: List<ProcessExit>): String? {
            if (latestCrash == null && exits.isEmpty()) return null
            return buildString {
                append("UltimateCamera ").append(environment.appVersion).append(" - ")
                append(environment.manufacturer).append(' ').append(environment.model).append(" - SDK ").append(environment.sdk).append("\n\n")
                append("== Last recorded crash ==\n")
                append(latestCrash ?: "(none recorded by the app)\n")
                if (exits.isNotEmpty()) {
                    append("\n== Recent abnormal exits (system record) ==\n")
                    exits.forEach { append(it.text).append("\n\n") }
                }
            }
        }
    }
}
