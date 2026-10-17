package com.qtekfun.ultimatecamera.core.camera

/**
 * Takes every ready frame from a reader that holds at most a few buffers (`ImageReader.acquireNextImage`), without
 * failing when all of them are still held by frames being written to disk: `acquireNextImage` then throws
 * IllegalStateException, the frame stays queued in the reader and is taken by the next call, made once a held frame is
 * closed. A three-frame HDR bracket crashed the photo thread on the Pixel 8 when this was a single unguarded acquire.
 */
object FrameDrain {
    /** Calls [offer] for each frame [acquire] returns until none is ready or the reader is full; returns how many. */
    fun <T : Any> drain(acquire: () -> T?, offer: (T) -> Unit): Int {
        var taken = 0
        while (true) {
            val frame = try {
                acquire()
            } catch (_: IllegalStateException) {
                return taken
            } ?: return taken
            offer(frame)
            taken++
        }
    }
}
