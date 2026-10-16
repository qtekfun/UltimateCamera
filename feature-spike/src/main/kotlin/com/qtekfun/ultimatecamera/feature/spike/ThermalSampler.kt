package com.qtekfun.ultimatecamera.feature.spike

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.serialization.Serializable

@Serializable
data class ThermalSample(
    val tSeconds: Double,
    /** PowerManager.THERMAL_STATUS_*: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
    val status: Int,
    /** Forecast headroom in 10 s; 1.0 means the device starts throttling severely. Null where the HAL does not report it. */
    val headroom: Float?,
    val batteryCelsius: Float?
)

class ThermalSampler(private val context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val start = SystemClock.elapsedRealtime()

    fun sample(): ThermalSample {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }
        return ThermalSample(
            tSeconds = (SystemClock.elapsedRealtime() - start) / 1_000.0,
            status = power.currentThermalStatus,
            headroom = power.getThermalHeadroom(10).takeIf { it.isFinite() },
            batteryCelsius = battery
        )
    }
}
