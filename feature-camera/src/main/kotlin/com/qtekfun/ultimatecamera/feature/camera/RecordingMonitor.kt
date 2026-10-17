package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.CameraState
import com.qtekfun.ultimatecamera.core.storage.FreeSpaceStatus
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.RecordingStreams
import com.qtekfun.ultimatecamera.core.storage.RecordingTimeEstimate
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the status bar shows; refreshed at [RecordingMonitor.TICK_MS]. */
data class StatusBarState(
    /** 0..100, or -1 when unknown. */
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    /** `PowerManager.THERMAL_STATUS_*`. */
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val recording: Boolean = false,
    /** Frames lost in the current take (expected from elapsed × fps, minus encoded). */
    val droppedFrames: Int = 0,
    /** p95 of the GPU stage's frame time, in tenths of a millisecond (an Int keeps equality cheap and stable). */
    val renderP95TenthsMs: Int = 0,
    /** Video bitrate actually written over the last seconds of the take, bits per second. */
    val bitrateBps: Long = 0
)

/**
 * Watches the phone while the camera screen is open (battery, heat) and the take while recording (dropped frames,
 * bitrate, free space, part size), publishes [state] for the status bar and asks [onAction] to stop, split or warn
 * through [RecordingGuard] (decision D47).
 *
 * Cheap on modest phones: one coroutine ticking at 2 Hz, battery read through `BatteryManager` properties, heat pushed
 * by a `PowerManager` listener, free space re-checked every [STORAGE_CHECK_MS] only while recording.
 */
class RecordingMonitor(
    private val context: Context,
    private val controller: CameraController,
    private val backend: StorageBackend,
    private val onAction: (GuardAction) -> Unit
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val guard = RecordingGuard()

    @Volatile private var thermal = PowerManager.THERMAL_STATUS_NONE
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { thermal = it }

    private val _state = MutableStateFlow(StatusBarState())
    val state: StateFlow<StatusBarState> = _state.asStateFlow()

    private var job: Job? = null

    // Take bookkeeping, only touched from the monitor coroutine and takeStarted (both on the main thread).
    private var location: RecordingLocation? = null
    private var streams: RecordingStreams? = null
    private var storageFull = false
    private var storageCheckedAt = 0L
    private var rateFromMs = 0L
    private var rateFromBytes = 0L
    private var bitrate = 0L

    /** Starts watching (camera screen in the foreground). */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        thermal = power.currentThermalStatus
        power.addThermalStatusListener(context.mainExecutor, thermalListener)
        job = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        power.removeThermalStatusListener(thermalListener)
    }

    /** A take started into [target] with [takeStreams]: re-arms the guard and the bitrate and storage checks. */
    fun takeStarted(target: RecordingLocation, takeStreams: RecordingStreams) {
        location = target
        streams = takeStreams
        storageFull = false
        val now = SystemClock.elapsedRealtime()
        storageCheckedAt = now
        rateFromMs = now
        rateFromBytes = 0
        bitrate = 0
        guard.reset(RecordingGuard.splitLimit(target))
    }

    private suspend fun tick() {
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..FULL_PERCENT } ?: -1
        val charging = battery.isCharging
        val heat = thermal
        val recording = controller.state.value as? CameraState.Recording
        val now = SystemClock.elapsedRealtime()
        var dropped = 0
        if (recording != null) {
            dropped = RecordingGuard.droppedFrames(now - recording.startedAtElapsedMs, recording.profile.fps, controller.framesEncoded())
            updateBitrate(now)
            if (now - storageCheckedAt >= STORAGE_CHECK_MS) checkStorage(now)
            val action = guard.decide(heat, percent, charging, storageFull, controller.partBytes())
            if (action != GuardAction.None) onAction(action)
        }
        _state.value = StatusBarState(
            batteryPercent = percent,
            charging = charging,
            thermalStatus = heat,
            recording = recording != null,
            droppedFrames = dropped,
            renderP95TenthsMs = (controller.renderP95Nanos() / NANOS_PER_TENTH_MS).toInt(),
            bitrateBps = if (recording != null) bitrate else 0
        )
    }

    private fun updateBitrate(now: Long) {
        if (now - rateFromMs < RATE_WINDOW_MS) return
        val bytes = controller.videoBytesEncoded()
        bitrate = RecordingGuard.bitsPerSecond(bytes - rateFromBytes, now - rateFromMs)
        rateFromBytes = bytes
        rateFromMs = now
    }

    private suspend fun checkStorage(now: Long) {
        storageCheckedAt = now
        val target = location ?: return
        val rates = streams ?: return
        val free = withContext(Dispatchers.IO) { runCatching { backend.freeBytes(target) }.getOrNull() } ?: return
        storageFull = RecordingTimeEstimate.classify(free, rates) == FreeSpaceStatus.FULL
    }

    companion object {
        /** 2 Hz: the status bar's refresh, and how fast heat, battery and part size are acted on. */
        const val TICK_MS = 500L

        /** Free space is a binder/statfs call: every 10 s is plenty at any bitrate (1 min of margin). */
        const val STORAGE_CHECK_MS = 10_000L

        const val RATE_WINDOW_MS = 2_000L
        private const val FULL_PERCENT = 100
        private const val NANOS_PER_TENTH_MS = 100_000L
    }
}
