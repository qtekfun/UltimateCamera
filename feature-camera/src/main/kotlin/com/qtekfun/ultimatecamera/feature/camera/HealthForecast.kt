package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import kotlin.math.ceil

/** What may end a take early. */
enum class HealthKind { BATTERY, STORAGE, THERMAL }

enum class AlertLevel { WARNING, CRITICAL }

/** A predicted problem: [kind] runs out (or overheats) in about [minutes]; 0 means now. */
data class HealthAlert(val kind: HealthKind, val level: AlertLevel, val minutes: Int)

/**
 * Battery drain rate measured during the session (D52): the least-squares slope of the battery percentage over a sliding
 * window of samples, so the integer steps of the percentage are smoothed out. Allocation-free: samples live in two
 * fixed arrays. Charging empties the window (the rate means nothing across a plug-in).
 */
class BatteryDrain(private val capacity: Int = DEFAULT_CAPACITY) {
    private val times = LongArray(capacity)
    private val levels = IntArray(capacity)
    private var count = 0
    private var next = 0

    fun clear() {
        count = 0
        next = 0
    }

    /** Adds a sample: [percent] at [elapsedMs] (monotonic). */
    fun add(elapsedMs: Long, percent: Int) {
        times[next] = elapsedMs
        levels[next] = percent
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** Drain in percent per minute (positive while draining), or null until the window spans [MIN_SPAN_MS]. */
    fun percentPerMinute(): Double? {
        if (count < 2) return null
        val oldest = if (count < capacity) 0 else next
        val t0 = times[oldest]
        val newest = (next - 1 + capacity) % capacity
        if (times[newest] - t0 < MIN_SPAN_MS) return null
        var sumT = 0.0
        var sumL = 0.0
        for (i in 0 until count) {
            sumT += (times[i] - t0).toDouble()
            sumL += levels[i].toDouble()
        }
        val meanT = sumT / count
        val meanL = sumL / count
        var cov = 0.0
        var varT = 0.0
        for (i in 0 until count) {
            val dt = (times[i] - t0) - meanT
            cov += dt * (levels[i] - meanL)
            varT += dt * dt
        }
        if (varT <= 0.0) return null
        return -cov / varT * MILLIS_PER_MINUTE
    }

    /** Minutes until 0 % at the measured rate, or null (not enough data, or not draining). */
    fun minutesLeft(percent: Int): Int? {
        val rate = percentPerMinute() ?: return null
        if (rate < MIN_RATE || percent < 0) return null
        return (percent / rate).toInt()
    }

    companion object {
        /** One sample every 10 s for 10 minutes. */
        const val DEFAULT_CAPACITY = 60
        const val SAMPLE_INTERVAL_MS = 10_000L

        /** Three minutes of samples before a rate is shown ("—" until then). */
        const val MIN_SPAN_MS = 3 * 60_000L

        /** Below 0.01 %/min (more than 160 h for a full battery) the phone is not really draining. */
        const val MIN_RATE = 0.01
        private const val MILLIS_PER_MINUTE = 60_000.0
    }
}

/**
 * Turns the measured rates into alerts (D52): battery or storage running out within [WARN_MINUTES], the thermal
 * headroom forecast reaching 1.0 (SEVERE), and critical below [CRITICAL_MINUTES]. Hysteresis keeps a banner from
 * flickering: an alert ends only above [CLEAR_MINUTES], and a thermal one [THERMAL_HOLD] evaluations after the forecast
 * cleared. Returns the same list instance while nothing changes (no allocation at 1 Hz in the steady state).
 */
class HealthForecast {
    private var batteryActive = false
    private var storageActive = false
    private var thermalHold = 0
    private var thermalMinutes = 0
    private var last: List<HealthAlert> = emptyList()

    /**
     * @param batteryMinutes minutes of battery at the measured rate (null: unknown).
     * @param storageMinutes minutes of recording left at the current mode (null: unknown).
     * @param thermalStatus `PowerManager.THERMAL_STATUS_*` now.
     * @param thermalMinutes minutes until the headroom forecast reaches SEVERE (null: not within the forecast).
     */
    fun evaluate(batteryMinutes: Int?, charging: Boolean, storageMinutes: Long?, thermalStatus: Int, thermalMinutes: Int?): List<HealthAlert> {
        val battery = if (charging) null else batteryMinutes
        batteryActive = active(batteryActive, battery?.toLong())
        storageActive = active(storageActive, storageMinutes)
        val hot = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        when {
            hot -> {
                thermalHold = THERMAL_HOLD
                this.thermalMinutes = 0
            }
            thermalMinutes != null -> {
                thermalHold = THERMAL_HOLD
                this.thermalMinutes = thermalMinutes
            }
            thermalHold > 0 -> thermalHold--
        }
        val alerts = buildList {
            if (thermalHold > 0) add(HealthAlert(HealthKind.THERMAL, level(this@HealthForecast.thermalMinutes.toLong()), this@HealthForecast.thermalMinutes))
            if (batteryActive && battery != null) add(HealthAlert(HealthKind.BATTERY, level(battery.toLong()), battery))
            if (storageActive && storageMinutes != null) add(HealthAlert(HealthKind.STORAGE, level(storageMinutes), storageMinutes.toInt()))
        }
        if (alerts != last) last = alerts
        return last
    }

    private fun active(wasActive: Boolean, minutes: Long?): Boolean = when {
        minutes == null -> false
        wasActive -> minutes < CLEAR_MINUTES
        else -> minutes < WARN_MINUTES
    }

    private fun level(minutes: Long) = if (minutes < CRITICAL_MINUTES) AlertLevel.CRITICAL else AlertLevel.WARNING

    companion object {
        const val WARN_MINUTES = 10L
        const val CLEAR_MINUTES = 12L
        const val CRITICAL_MINUTES = 2L

        /** Evaluations (seconds at 1 Hz) a thermal alert stays after its forecast cleared. */
        const val THERMAL_HOLD = 10

        /** Horizon of `PowerManager.getThermalHeadroom` used for the forecast (the API accepts 0..60 s). */
        const val FORECAST_SECONDS = 60

        /** The platform forecast covers a minute; the trend is extended linearly up to this many seconds (5 min). */
        const val MAX_HORIZON_SECONDS = 300f

        /**
         * Minutes until the thermal headroom reaches 1.0 (SEVERE), extending the line from [now] to the [forecast] in
         * [forecastSeconds] up to [MAX_HORIZON_SECONDS]; 0 when already there, null when the headroom is not rising
         * fast enough or is unknown (NaN: unsupported or polled too often).
         */
        fun minutesToSevere(now: Float, forecast: Float, forecastSeconds: Int): Int? {
            if (now.isNaN()) return null
            if (now >= 1f) return 0
            if (forecast.isNaN() || forecast <= now) return null
            val seconds = forecastSeconds * (1f - now) / (forecast - now)
            return if (seconds > MAX_HORIZON_SECONDS) null else ceil(seconds / SECONDS_PER_MINUTE - ROUNDING_SLACK).toInt().coerceAtLeast(1)
        }

        private const val SECONDS_PER_MINUTE = 60f

        /** Float noise (2.0000001 min) must not round up to the next minute. */
        private const val ROUNDING_SLACK = 1e-3f
    }
}
