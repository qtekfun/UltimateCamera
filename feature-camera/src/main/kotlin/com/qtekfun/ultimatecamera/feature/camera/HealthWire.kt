package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import com.qtekfun.ultimatecamera.core.net.control.HealthAlertInfo
import com.qtekfun.ultimatecamera.core.net.control.HealthAlertKind
import com.qtekfun.ultimatecamera.core.net.control.HealthDetail
import com.qtekfun.ultimatecamera.core.net.control.ThermalLevel
import com.qtekfun.ultimatecamera.core.net.role.DeviceHealth

/**
 * The camera screen's health as core-net's status (D52), for `CameraNode.updateHealth` once the camera role is wired:
 * the summary fields older monitors already show, plus [HealthDetail] with the indicators and the active alerts.
 */
fun HealthState.toDeviceHealth(audioSource: String? = null): DeviceHealth = DeviceHealth(
    batteryPercent = batteryPercent.takeIf { it >= 0 },
    charging = charging,
    thermal = thermalLevel(thermalStatus),
    freeSpaceMinutes = storageMinutesLeft?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
    audioSource = audioSource,
    detail = HealthDetail(
        batteryMinutes = batteryMinutesLeft,
        batteryTempTenthsC = batteryTempTenthsC,
        thermalHeadroomPercent = thermalHeadroom?.let { (it * PERCENT).toInt() },
        storageMinutes = storageMinutesLeft?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
        freeMegabytes = freeBytes?.let { it / BYTES_PER_MB },
        alerts = alerts.map { HealthAlertInfo(wireKind(it.kind), critical = it.level == AlertLevel.CRITICAL, minutes = it.minutes) }
    )
)

internal fun thermalLevel(status: Int): ThermalLevel = when {
    status >= PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SHUTDOWN
    status >= PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalLevel.EMERGENCY
    status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
    status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
    status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
    status >= PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
    else -> ThermalLevel.NONE
}

private fun wireKind(kind: HealthKind): HealthAlertKind = when (kind) {
    HealthKind.BATTERY -> HealthAlertKind.BATTERY
    HealthKind.STORAGE -> HealthAlertKind.STORAGE
    HealthKind.THERMAL -> HealthAlertKind.THERMAL
}

private const val PERCENT = 100
private const val BYTES_PER_MB = 1_000_000L
