package com.qtekfun.ultimatecamera.core.capabilities

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/** Android bindings of the probe engine's ports (not unit-tested: they only read the platform). */
object AndroidCapabilities {
    fun identity(): DeviceIdentity = DeviceIdentity(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        model = Build.MODEL,
        device = Build.DEVICE,
        sdk = Build.VERSION.SDK_INT,
        buildId = Build.ID,
        fingerprint = Build.FINGERPRINT
    )

    /** In `noBackupFilesDir`: private to the app and excluded from any backup (docs/01: regenerable, not backed up). */
    fun store(context: Context): CapabilityStore = CapabilityStore.inNoBackupDir(context.noBackupFilesDir)

    fun thermalMonitor(context: Context): ThermalMonitor {
        val power = context.getSystemService(PowerManager::class.java)
        return ThermalMonitor { power?.currentThermalStatus ?: ThermalStatus.NONE }
    }

    val clock = MonotonicClock { SystemClock.elapsedRealtime() }
}
