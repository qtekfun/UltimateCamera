package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CaptureFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateTest {
    @Test
    fun `the fixed range comes first even when the camera does not list it`() {
        // vivo X500 Pro Max style list: no [60, 60], only a variable range reaching 60.
        val advertised = listOf(15..30, 30..30, 30..60, 15..60)
        assertEquals(listOf(60..60, 30..60, 15..60), CameraStream.fpsRangeCandidates(advertised, 60))
    }

    @Test
    fun `a listed fixed range is not repeated`() {
        assertEquals(listOf(30..30, 15..30), CameraStream.fpsRangeCandidates(listOf(15..30, 30..30), 30))
    }

    @Test
    fun `no listed ranges still try the fixed one`() {
        assertEquals(listOf(24..24), CameraStream.fpsRangeCandidates(emptyList(), 24))
    }

    @Test
    fun `camera rate from capture results`() {
        val start = FrameClock(frames = 100, lastTimestampNs = 1_000_000_000L)
        // 120 frames over 4 s: the camera ran at 30 fps.
        assertEquals(30.0, FrameClock(220, 5_000_000_000L).fpsSince(start)!!, 1e-9)
    }

    @Test
    fun `a stretched frame counts as skipped by the camera, regular steps and jitter do not`() {
        // 60 fps: 16.7 ms is regular, 20 ms is jitter, 33.3 ms is one frame left out, 50 ms two.
        assertEquals(0, FrameClock.skippedIn(16_666_667L, 60))
        assertEquals(0, FrameClock.skippedIn(20_000_000L, 60))
        assertEquals(1, FrameClock.skippedIn(33_333_333L, 60))
        assertEquals(2, FrameClock.skippedIn(50_000_000L, 60))
        assertEquals(1, FrameClock.skippedIn(66_666_667L, 30))
        assertEquals(0, FrameClock.skippedIn(0L, 60))
        assertEquals(0, FrameClock.skippedIn(33_333_333L, 0))
    }

    @Test
    fun `no rate before the first result or without new frames`() {
        assertNull(FrameClock(10, 2_000L).fpsSince(FrameClock(0, -1L)))
        assertNull(FrameClock(5, 2_000L).fpsSince(FrameClock(5, 1_000L)))
    }

    @Test
    fun `lost buffers are counted between two readings and a flushed capture is not one`() {
        // D104: a reading carries the buffers the camera reported lost; a window takes the difference.
        val start = FrameClock(100, 1_000_000_000L, buffersLost = 2)
        val end = FrameClock(340, 5_000_000_000L, buffersLost = 5)
        assertEquals(3, end.buffersLost - start.buffersLost)
        assertEquals(0, FrameClock(0, -1L).buffersLost)
        assertTrue(FrameClock.lostOnFailure(CaptureFailure.REASON_ERROR))
        assertFalse(FrameClock.lostOnFailure(CaptureFailure.REASON_FLUSHED))
    }

    @Test
    fun `the record use case is asked only when requested`() {
        assertFalse(StreamSpec("0", 3840, 2160, 60).recordUseCase)
        assertTrue(StreamSpec("0", 3840, 2160, 60, recordUseCase = true).recordUseCase)
    }

    @Test
    fun `a refused fixed range falls back to the next candidate`() = runBlocking {
        val tried = mutableListOf<IntRange>()
        val chosen = CameraStream.firstAccepted(listOf(60..60, 30..60)) { r ->
            tried += r
            if (r == 60..60) throw SessionRejectedException("refused") else r
        }
        assertEquals(30..60, chosen)
        assertEquals(listOf(60..60, 30..60), tried)
    }

    @Test
    fun `the last refusal is thrown when every candidate is refused`() {
        val e = assertThrows(SessionRejectedException::class.java) {
            runBlocking { CameraStream.firstAccepted(listOf(1, 2)) { throw SessionRejectedException("no $it") } }
        }
        assertEquals("no 2", e.message)
    }

    @Test
    fun `other failures are not swallowed`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { CameraStream.firstAccepted(listOf(1, 2)) { error("camera died") } }
        }
    }
}
