package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
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

/** The phone's health for the always-visible indicators, the banner and the remote monitor (D52); 1 Hz. */
data class HealthState(
    /** 0..100, or -1 when unknown. */
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    /** Minutes of battery at the drain measured this session; null until [BatteryDrain] has enough data. */
    val batteryMinutesLeft: Int? = null,
    /** Battery temperature in tenths of °C (`EXTRA_TEMPERATURE`); null when not reported. */
    val batteryTempTenthsC: Int? = null,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    /** `getThermalHeadroom(0)`: 1.0 is SEVERE; null when the phone does not support it. */
    val thermalHeadroom: Float? = null,
    /** Minutes of recording left at the current mode on the effective location. */
    val storageMinutesLeft: Long? = null,
    val freeBytes: Long? = null,
    val alerts: List<HealthAlert> = emptyList()
)

/**
 * Watches the phone while the camera screen is open (battery, heat, storage) and the take while recording (dropped
 * frames, bitrate, part size), publishes [state] for the status bar and [health] for the indicators and the banner,
 * and asks [onAction] to stop, split or warn through [RecordingGuard] (decisions D47, D52).
 *
 * Cheap on modest phones: one coroutine ticking at 2 Hz; health (battery temperature, thermal headroom, forecasts) is
 * computed every other tick (1 Hz); heat status is pushed by a `PowerManager` listener; free space is re-checked every
 * [STORAGE_CHECK_MS]. `getThermalHeadroom` may not be polled faster than 1 Hz, so the current value and the forecast
 * are read on alternate health ticks.
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
    private val drain = BatteryDrain()
    private val forecast = HealthForecast()
    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    @Volatile private var thermal = PowerManager.THERMAL_STATUS_NONE
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { thermal = it }

    private val _state = MutableStateFlow(StatusBarState())
    val state: StateFlow<StatusBarState> = _state.asStateFlow()

    private val _health = MutableStateFlow(HealthState())
    val health: StateFlow<HealthState> = _health.asStateFlow()

    /** A short vibration on the first critical alert of each kind (Settings, on by default). */
    @Volatile var vibrateOnCritical = true

    private var job: Job? = null

    // Only touched from the monitor coroutine and the view model's calls (both on the main thread).
    private var location: RecordingLocation? = null
    private var streams: RecordingStreams? = null
    private var freeBytes: Long? = null
    private var storageFull = false
    private var storageCheckedAt = 0L
    private var rateFromMs = 0L
    private var rateFromBytes = 0L
    private var bitrate = 0L
    private var ticks = 0L
    private var lastDrainSampleAt = Long.MIN_VALUE
    private var wasCharging = false
    private var headroomNow = Float.NaN
    private var headroomForecast = Float.NaN
    private val vibrated = BooleanArray(HealthKind.entries.size)

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

    /**
     * Where the next take goes and at which rates (null when there is nowhere to record), with the free space just
     * measured: the storage indicator and forecast follow it, also between takes.
     */
    fun setStorageTarget(target: RecordingLocation?, targetStreams: RecordingStreams?, free: Long?) {
        location = target
        streams = targetStreams
        freeBytes = free
        storageCheckedAt = SystemClock.elapsedRealtime()
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
        if (now - storageCheckedAt >= STORAGE_CHECK_MS) checkStorage(now)
        var dropped = 0
        if (recording != null) {
            dropped = RecordingGuard.droppedFrames(now - recording.startedAtElapsedMs, recording.profile.fps, controller.framesEncoded())
            updateBitrate(now)
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
        if (ticks++ % HEALTH_EVERY_TICKS == 0L) updateHealth(now, percent, charging, heat)
    }

    private fun updateHealth(now: Long, percent: Int, charging: Boolean, heat: Int) {
        if (charging != wasCharging) {
            drain.clear()
            wasCharging = charging
        }
        if (!charging && percent >= 0 && now - lastDrainSampleAt >= BatteryDrain.SAMPLE_INTERVAL_MS) {
            drain.add(now, percent)
            lastDrainSampleAt = now
        }
        // Alternate: one headroom call per second at most (the platform returns NaN when polled faster).
        if (ticks / HEALTH_EVERY_TICKS % 2 == 0L) {
            headroomNow = power.getThermalHeadroom(0)
        } else {
            headroomForecast = power.getThermalHeadroom(HealthForecast.FORECAST_SECONDS)
        }
        val batteryMinutes = if (charging) null else drain.minutesLeft(percent)
        val rates = streams
        val free = freeBytes
        val storageMinutes = if (location != null && rates != null && free != null) RecordingTimeEstimate.remainingMinutes(free, rates) else null
        val thermalMinutes = HealthForecast.minutesToSevere(headroomNow, headroomForecast, HealthForecast.FORECAST_SECONDS)
        val alerts = forecast.evaluate(batteryMinutes, charging, storageMinutes, heat, thermalMinutes)
        vibrateOnFirstCritical(alerts)
        _health.value = HealthState(
            batteryPercent = percent,
            charging = charging,
            batteryMinutesLeft = batteryMinutes,
            batteryTempTenthsC = batteryTemperature(),
            thermalStatus = heat,
            thermalHeadroom = headroomNow.takeUnless { it.isNaN() },
            storageMinutesLeft = storageMinutes,
            freeBytes = free,
            alerts = alerts
        )
    }

    /** The sticky battery broadcast's temperature (no receiver is registered). */
    private fun batteryTemperature(): Int? = context.registerReceiver(null, batteryFilter)
        ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?.takeIf { it != Int.MIN_VALUE }

    private fun vibrateOnFirstCritical(alerts: List<HealthAlert>) {
        for (alert in alerts) {
            if (alert.level != AlertLevel.CRITICAL || vibrated[alert.kind.ordinal]) continue
            vibrated[alert.kind.ordinal] = true
            if (vibrateOnCritical) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                    ?.vibrate(VibrationEffect.createOneShot(VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
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
        freeBytes = free
        storageFull = RecordingTimeEstimate.classify(free, rates) == FreeSpaceStatus.FULL
    }

    companion object {
        /** 2 Hz: the status bar's refresh, and how fast heat, battery and part size are acted on. */
        const val TICK_MS = 500L

        /** Health (indicators, forecasts, banner) every other tick: 1 Hz. */
        const val HEALTH_EVERY_TICKS = 2L

        /** Free space is a binder/statfs call: every 10 s is plenty at any bitrate (1 min of margin). */
        const val STORAGE_CHECK_MS = 10_000L

        const val RATE_WINDOW_MS = 2_000L
        private const val FULL_PERCENT = 100
        private const val NANOS_PER_TENTH_MS = 100_000L
        private const val VIBRATION_MS = 300L
    }
}
