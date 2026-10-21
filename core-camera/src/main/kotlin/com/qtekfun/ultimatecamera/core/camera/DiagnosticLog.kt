package com.qtekfun.ultimatecamera.core.camera

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue

/**
 * Optional diagnostic log (D113): Settings › Diagnostic log turns it on, and while it is on the camera paths write what
 * they see and decide (lens in use, white balance measurements and gains, sessions, errors) as text lines in
 * app-private storage, so a problem seen away from a computer can be analysed afterwards. Off by default: it costs a
 * background thread and some disk writes. Lines carry no device identifier, account or location; nothing leaves the
 * phone unless the user shares the file.
 *
 * [log] never blocks the caller: lines go to a bounded queue (dropped when full) that one daemon thread appends to
 * [FILE_NAME], which rolls over to [OLD_FILE_NAME] past [MAX_BYTES]. The on/off choice survives restarts as a marker file.
 */
object DiagnosticLog {
    const val FILE_NAME = "diagnostic.log"
    const val OLD_FILE_NAME = "diagnostic.1.log"
    private const val ENABLED_MARKER = "enabled"

    /** Each file's size before it rolls over; two files, so at most twice this on disk. */
    const val MAX_BYTES = 2L * 1024 * 1024
    private const val MAX_QUEUED = 2_000

    @Volatile private var dir: File? = null

    @Volatile var enabled: Boolean = false
        private set

    private val queue = LinkedBlockingQueue<String>(MAX_QUEUED)
    private var writer: Thread? = null

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    /** Where the log lives (an app-private folder); call once at start. Turns the log on if it was left on. */
    fun init(directory: File) {
        dir = directory
        enabled = File(directory, ENABLED_MARKER).exists()
        if (enabled) startWriter()
    }

    /** Turns the log on or off (remembered across restarts). */
    fun setEnabled(on: Boolean) {
        val d = dir ?: return
        d.mkdirs()
        val marker = File(d, ENABLED_MARKER)
        runCatching { if (on) marker.createNewFile() else marker.delete() }
        if (on) startWriter()
        enabled = on
        write("log", if (on) "on" else "off")
    }

    /** Adds a line when the log is on; [message] is only built then. */
    inline fun log(tag: String, message: () -> String) {
        if (enabled) write(tag, message())
    }

    fun write(tag: String, message: String) {
        queue.offer(line(System.currentTimeMillis(), tag, message))
    }

    /** The whole log, oldest first (the rolled-over file, then the current one); empty when nothing was written. */
    fun text(): String {
        val d = dir ?: return ""
        return listOf(OLD_FILE_NAME, FILE_NAME).map { File(d, it) }.filter { it.isFile }.joinToString("") { runCatching { it.readText() }.getOrDefault("") }
    }

    /** Bytes on disk for the log. */
    fun size(): Long = dir?.let { d -> listOf(OLD_FILE_NAME, FILE_NAME).sumOf { File(d, it).length() } } ?: 0L

    /** Deletes the log files (the on/off choice stays). */
    fun clear() {
        queue.clear()
        dir?.let { d -> listOf(OLD_FILE_NAME, FILE_NAME).forEach { File(d, it).delete() } }
    }

    /** `2026-10-21 20:35:12.345 tag message`, one line (line breaks in [message] become spaces). */
    fun line(epochMs: Long, tag: String, message: String): String = "${timeFormat.format(Instant.ofEpochMilli(epochMs))} $tag ${message.replace('\n', ' ')}\n"

    /** Appends [lines] to [directory]'s log, rolling the current file over first when it has passed [maxBytes]. */
    fun append(directory: File, lines: List<String>, maxBytes: Long = MAX_BYTES) {
        if (lines.isEmpty()) return
        directory.mkdirs()
        val current = File(directory, FILE_NAME)
        if (current.length() >= maxBytes) {
            val old = File(directory, OLD_FILE_NAME)
            old.delete()
            current.renameTo(old)
        }
        current.appendText(lines.joinToString(""))
    }

    @Synchronized
    private fun startWriter() {
        if (writer?.isAlive == true) return
        writer = Thread({
            val batch = ArrayList<String>()
            while (true) {
                batch.add(queue.take())
                queue.drainTo(batch)
                dir?.let { d -> runCatching { append(d, batch) } }
                batch.clear()
            }
        }, "uc-diagnostic-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }
}
