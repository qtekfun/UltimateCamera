package com.qtekfun.ultimatecamera.feature.multicam.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing QR scanner's lifecycle without Camera2: restart after the surface comes back, closed cameras, stale callbacks. */
class ScannerCoreTest {
    /** A thread that runs its queue on [drain] and refuses work once quit (like a quit HandlerThread). */
    private class FakeWorker : ScannerWorker {
        val queue = ArrayDeque<() -> Unit>()
        var quit = false

        override fun post(block: () -> Unit): Boolean {
            if (quit) return false
            queue += block
            return true
        }

        override fun quit() {
            // quitSafely: what is queued still runs.
            quit = true
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst()()
        }
    }

    private class Camera(val name: String) {
        var closed = false
    }

    private class Session {
        var closed = false
    }

    private val workers = ArrayList<FakeWorker>()
    private var errors = 0
    private val core = ScannerCore<FakeWorker, Camera, Session>(
        newWorker = { FakeWorker().also { workers += it } },
        closeDevice = { it.closed = true },
        closeSession = { it.closed = true },
        onError = { errors++ }
    )

    private fun drainAll() = workers.toList().forEach { it.drain() }

    /** Opens and returns the generation [start] ran with (null if it never ran). */
    private fun openNow(): Int? {
        var started: Int? = null
        core.open { gen, _ -> started = gen }
        drainAll()
        return started
    }

    @Test
    fun theScannerStartsAgainWhenTheSurfaceComesBack() {
        val first = openNow()!!
        val camera = Camera("first")
        assertTrue(core.onOpened(first, camera))
        core.stop() // The app went to the background: the TextureView's surface is destroyed.
        drainAll()
        assertTrue("the camera is released while the surface is gone", camera.closed)
        // Before: the surface going away quit the thread for good, and this open ran on a dead thread (nothing happened).
        val second = openNow()
        assertTrue("the scanner opens again", second != null && second != first)
        assertTrue(core.onOpened(second!!, Camera("second")))
        assertEquals(0, errors)
    }

    @Test
    fun aCameraClosedBeforeTheSessionIsConfiguredIsAnErrorNotACrash() {
        val gen = openNow()!!
        val camera = Camera("cam")
        core.onOpened(gen, camera)
        val session = Session()
        // createCaptureRequest on a camera closed meanwhile (the camera screen still releasing it) throws: before, uncaught
        // on the scanner thread, which killed the app.
        core.onConfigured(gen, camera, session) { throw IllegalStateException("CameraDevice was already closed") }
        assertEquals(1, errors)
        assertTrue(session.closed)
        assertTrue(camera.closed)
    }

    @Test
    fun aDisconnectedCameraIsForgottenSoALateSessionIsNotStarted() {
        val gen = openNow()!!
        val camera = Camera("cam")
        core.onOpened(gen, camera)
        core.onLost(camera)
        assertTrue(camera.closed)
        assertEquals(1, errors)
        val session = Session()
        var started = false
        core.onConfigured(gen, camera, session) { started = true }
        assertFalse("nothing is started on a camera that went away", started)
        assertTrue(session.closed)
        core.stop()
        drainAll()
        assertEquals(1, errors)
    }

    @Test
    fun callbacksOfAnOlderOpenCloseWhatTheyBring() {
        val old = openNow()!!
        core.stop()
        val fresh = openNow()!!
        val late = Camera("late")
        assertFalse(core.onOpened(old, late))
        assertTrue(late.closed)
        val camera = Camera("cam")
        assertTrue(core.onOpened(fresh, camera))
        val reader = AutoCloseable { throw IllegalStateException("already closed") }
        core.attach(old, reader) // Closing throws: ignored.
        val staleSession = Session()
        core.onConfigured(old, camera, staleSession) { error("must not start") }
        assertTrue(staleSession.closed)
        core.failed(old)
        assertEquals(0, errors)
        assertFalse(camera.closed)
    }

    @Test
    fun closeReleasesQuitsAndRefusesFurtherOpens() {
        val gen = openNow()!!
        val camera = Camera("cam")
        core.onOpened(gen, camera)
        var readerClosed = false
        core.attach(gen, AutoCloseable { readerClosed = true })
        core.close()
        drainAll()
        assertTrue(camera.closed)
        assertTrue(readerClosed)
        assertTrue(workers.single().quit)
        assertEquals(null, openNow())
        assertEquals(1, workers.size)
        core.post { error("no worker after close") }
        core.close()
    }

    @Test
    fun aWorkerThatRefusesWorkIsAnError() {
        core.open { _, _ -> }
        workers.single().quit = true
        core.open { _, _ -> error("not run") }
        assertEquals(1, errors)
    }

    @Test
    fun configureFailureOfTheCurrentOpenIsAnError() {
        val gen = openNow()!!
        val camera = Camera("cam")
        core.onOpened(gen, camera)
        val session = Session()
        var started = false
        core.onConfigured(gen, camera, session) { started = true }
        assertTrue(started)
        core.failed(gen)
        assertEquals(1, errors)
        assertTrue(session.closed)
        assertTrue(camera.closed)
    }
}
