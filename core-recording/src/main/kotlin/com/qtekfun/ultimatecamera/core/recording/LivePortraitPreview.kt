package com.qtekfun.ultimatecamera.core.recording

import android.util.Log
import com.qtekfun.ultimatecamera.core.camera.DepthFrame
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepth
import com.qtekfun.ultimatecamera.core.gpu.portrait.LiveDepthGate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The depth side of the live portrait preview (D87): live depth frames from the camera thread go, through a
 * [LiveDepthGate] (one at a time, at most one per [minIntervalNanos], the rest dropped), to one low-priority worker
 * that turns them into a [LiveDepth] for the current [look] ([PortraitMaps.liveDepth]) and hands it to [deliver] (the
 * renderer's `setLiveDepth`). Nothing here can hold the camera thread or the viewfinder.
 */
internal class LivePortraitPreview(
    minIntervalNanos: Long,
    private val look: () -> PortraitLook,
    private val deliver: (LiveDepth) -> Unit,
    private val now: () -> Long = System::nanoTime,
    private val onSkip: (String) -> Unit = { Log.w(TAG, "live depth skipped: $it") },
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "uc-portrait-live").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
) {
    private val gate = LiveDepthGate(minIntervalNanos)

    /** Frames dropped by the gate since the start (the log shows how much the phone kept up). */
    val dropped: Int get() = gate.dropped

    /** A live depth frame (camera thread): queued for the worker, or dropped when it is busy or too soon. */
    @Suppress("TooGenericExceptionCaught") // A frame that cannot be turned into depth is just skipped.
    fun onDepth(frame: DepthFrame) {
        if (!gate.tryAcquire(now())) return
        try {
            worker.execute {
                try {
                    deliver(PortraitMaps.liveDepth(frame, look()))
                } catch (e: RuntimeException) {
                    onSkip("${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    gate.release()
                }
            }
        } catch (_: RejectedExecutionException) {
            gate.release() // Closed meanwhile.
        }
    }

    fun close() {
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "UcPortrait"
    }
}
