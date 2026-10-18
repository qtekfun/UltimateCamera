package com.qtekfun.ultimatecamera.core.camera

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.SelectClause1

/**
 * The frames of one output of a portrait session (D91) between its ImageReader and [DepthCaptureStream.capture]. A
 * reader holds at most a few buffers and `acquireNextImage` throws once they are all acquired, so the queue keeps only
 * the newest [keep] frames and closes the older ones as soon as a newer frame arrives: during a shot with live depth
 * (D87) the preview's in-flight depth frames, the still's and, for a stereo pair, the second camera's frames waiting
 * while the first is read, otherwise piled up until the stereo session crashed the photo thread on the Pixel 8
 * ("maxImages (2) has already been acquired"). The still is the last request that fills the depth outputs, so dropping
 * the oldest never drops it.
 *
 * Frames are taken from the reader with [FrameDrain]; when all buffers are held the rest wait in the reader and are
 * taken by the next [onAvailable] (call it again after closing a frame). Thread-safe.
 */
class PortraitFrames<T : Any>(val keep: Int, private val close: (T) -> Unit) {
    private val channel = Channel<T>(keep, BufferOverflow.DROP_OLDEST, onUndeliveredElement = close)

    /** Receives the oldest queued frame (for `select`). A frame received by a cancelled receiver is closed. */
    val onReceive: SelectClause1<T> get() = channel.onReceive

    suspend fun receive(): T = channel.receive()

    /**
     * Takes every ready frame of a reader through [acquire]: into the queue while [queue] is true (a shot is waiting
     * for it, or the output has no live consumer); otherwise the stale queued frames are closed first and each frame
     * goes to [live] and is closed right after. Returns how many frames were taken.
     */
    fun onAvailable(acquire: () -> T?, queue: Boolean, live: (T) -> Unit = {}): Int = FrameDrain.drain(acquire) { frame ->
        if (queue) {
            offer(frame)
        } else {
            discard()
            try {
                live(frame)
            } finally {
                close(frame)
            }
        }
    }

    /** Queues [frame], closing the oldest one beyond [keep] (or [frame] itself once the queue is closed). */
    fun offer(frame: T) {
        if (channel.trySend(frame).isFailure) close(frame)
    }

    /** Closes every queued frame (before a shot, after one, and when the session closes). */
    fun discard() {
        while (true) close(channel.tryReceive().getOrNull() ?: return)
    }

    /** Closes the queued frames and the queue; later frames are closed on arrival. */
    fun shutdown() {
        channel.close()
        discard()
    }
}
