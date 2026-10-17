package com.qtekfun.ultimatecamera.feature.multicam.pairing

/** The scanner's background thread. [post] returns false once it has quit; [quit] lets queued work run first. */
internal interface ScannerWorker {
    fun post(block: () -> Unit): Boolean

    fun quit()
}

/**
 * Lifecycle of the pairing QR scanner, kept apart from Camera2 so it runs on the JVM ([QrScanner] is the Camera2 glue).
 *
 * - The worker thread lives until [close]; a preview surface that goes away ([stop], e.g. the app goes to the background)
 *   only releases the camera, and the next [open] (surface back) starts again on the same thread, or on a new one if it
 *   was closed. Before, the surface going away quit the thread for good and the scanner stayed silently dead.
 * - Every [open] is a new generation: callbacks of an older one (a camera opening or a session configuring after the
 *   surface went away) close what they bring and do nothing else.
 * - A camera that disconnects or fails is closed *and forgotten*, and building or starting the capture request on a
 *   camera that was closed meanwhile (the camera screen or a background service still releasing it) is an error on
 *   screen, never an uncaught `IllegalStateException` on the scanner thread.
 *
 * [D] is the camera, [S] the capture session; device, session and resources are only touched on the worker.
 */
internal class ScannerCore<W : ScannerWorker, D : Any, S : Any>(
    private val newWorker: () -> W,
    private val closeDevice: (D) -> Unit,
    private val closeSession: (S) -> Unit,
    private val onError: () -> Unit
) {
    private var worker: W? = null

    @Volatile private var generation = 0

    @Volatile private var released = false
    private var device: D? = null
    private var session: S? = null
    private val resources = ArrayList<AutoCloseable>()

    /** True while [gen] is the newest [open] and the scanner is not closed. */
    fun isCurrent(gen: Int): Boolean = gen == generation && !released

    /** The surface is ready: runs [start] on the worker (started if needed) with a new generation. */
    fun open(start: (gen: Int, worker: W) -> Unit) {
        val w: W
        val gen: Int
        synchronized(this) {
            if (released) return
            w = worker ?: newWorker().also { worker = it }
            gen = ++generation
        }
        if (!w.post { if (isCurrent(gen)) start(gen, w) }) onError()
    }

    /** Runs [block] on the worker, if there is one. */
    fun post(block: () -> Unit) {
        synchronized(this) { worker }?.post(block)
    }

    /** A resource of generation [gen] (the image reader), closed with the camera; at once if [gen] is stale. Worker. */
    fun attach(gen: Int, resource: AutoCloseable) {
        if (isCurrent(gen)) resources += resource else quietly { resource.close() }
    }

    /** The camera opened; false (and closed) when [gen] is stale. Worker. */
    fun onOpened(gen: Int, camera: D): Boolean {
        if (!isCurrent(gen)) {
            quietly { closeDevice(camera) }
            return false
        }
        device = camera
        return true
    }

    /** The camera went away (another client took it) or failed: closed, forgotten, and said on screen. Worker. */
    fun onLost(camera: D) {
        quietly { closeDevice(camera) }
        if (device !== camera) return
        device = null
        releaseCamera()
        onError()
    }

    /**
     * The session of [camera] is configured: [start] builds and starts the repeating request. A stale generation, a
     * camera that is gone, or a [start] that throws (camera closed meanwhile) closes the session; the last is an error.
     * Worker.
     */
    @Suppress("TooGenericExceptionCaught") // Camera2 boundary: a closed camera throws IllegalStateException, others vary.
    fun onConfigured(gen: Int, camera: D, configured: S, start: () -> Unit) {
        if (!isCurrent(gen) || device !== camera) {
            quietly { closeSession(configured) }
            return
        }
        session = configured
        try {
            start()
        } catch (_: Exception) {
            failed(gen)
        }
    }

    /** Opening, configuring or starting failed for [gen]: the camera is released and, if still current, it is said. Worker. */
    fun failed(gen: Int) {
        if (!isCurrent(gen)) return
        releaseCamera()
        onError()
    }

    /** The surface went away: the camera is released, the worker kept for the next [open]. */
    fun stop() {
        val w = synchronized(this) {
            generation++
            worker
        } ?: return
        w.post(::releaseCamera)
    }

    /** For good: the camera is released and the worker quits. */
    fun close() {
        val w = synchronized(this) {
            released = true
            generation++
            worker.also { worker = null }
        } ?: return
        w.post(::releaseCamera)
        w.quit()
    }

    private fun releaseCamera() {
        session?.let { s -> quietly { closeSession(s) } }
        device?.let { d -> quietly { closeDevice(d) } }
        resources.forEach { r -> quietly { r.close() } }
        session = null
        device = null
        resources.clear()
    }

    @Suppress("TooGenericExceptionCaught") // Closing a camera that is already gone may throw anything; it is gone either way.
    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Already closed.
        }
    }
}
