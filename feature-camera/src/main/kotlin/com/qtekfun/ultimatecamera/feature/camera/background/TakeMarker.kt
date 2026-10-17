package com.qtekfun.ultimatecamera.feature.camera.background

import java.io.File
import java.io.IOException

/**
 * A small file that exists only while a take is being written (D66). MediaMuxer writes the MP4 index (moov) at stop, so
 * a take whose process is killed mid-way cannot be recovered; the marker at least lets the next start say so.
 */
class TakeMarker(private val file: File) {
    /** A take named [fileName] started (or moved on to a new part). */
    fun begin(fileName: String) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(fileName)
        } catch (_: IOException) {
            // Best effort: losing the note never touches the take.
        }
    }

    /** The take was closed cleanly. */
    fun end() {
        file.delete()
    }

    /** The name of a take that was still being written when the app last went away without closing it, then forgets it. */
    fun consumeInterrupted(): String? {
        if (!file.exists()) return null
        val name = try {
            file.readText().trim().ifEmpty { UNKNOWN }
        } catch (_: IOException) {
            UNKNOWN
        }
        file.delete()
        return name
    }

    companion object {
        const val FILE_NAME = "take-in-progress"
        const val UNKNOWN = "?"

        @Volatile private var checked = false

        /**
         * [consumeInterrupted] once per process: a second camera screen in the same process (activity recreated while
         * the previous one was still closing its take) must not report its predecessor's take as interrupted.
         */
        @Synchronized
        fun consumeOncePerProcess(marker: TakeMarker): String? {
            if (checked) return null
            checked = true
            return marker.consumeInterrupted()
        }
    }
}
