package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import com.qtekfun.ultimatecamera.core.net.control.HealthAlertKind
import com.qtekfun.ultimatecamera.core.net.control.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthForecastTest {
    private val cool = PowerManager.THERMAL_STATUS_NONE

    @Test
    fun drainNeedsThreeMinutesOfSamples() {
        val drain = BatteryDrain()
        drain.add(0, 50)
        drain.add(60_000, 49)
        assertNull(drain.minutesLeft(49))
        drain.add(180_000, 47)
        assertTrue(drain.minutesLeft(47) != null)
    }

    @Test
    fun drainIsTheSmoothedSlope() {
        // 1 % every minute, sampled every 10 s with integer steps.
        val drain = BatteryDrain()
        for (s in 0..600 step 10) drain.add(s * 1_000L, 80 - s / 60)
        val rate = drain.percentPerMinute()!!
        assertEquals(1.0, rate, 0.05)
        assertEquals(70.0, drain.minutesLeft(70)!!.toDouble(), 3.0)
    }

    @Test
    fun steadyOrRisingBatteryHasNoEstimate() {
        val drain = BatteryDrain()
        for (s in 0..600 step 10) drain.add(s * 1_000L, 60)
        assertNull(drain.minutesLeft(60))
        drain.clear()
        for (s in 0..600 step 10) drain.add(s * 1_000L, 60 + s / 60)
        assertNull(drain.minutesLeft(70))
    }

    @Test
    fun windowForgetsOldSamples() {
        val drain = BatteryDrain(capacity = 4)
        listOf(0L to 90, 60_000L to 80, 120_000L to 70, 180_000L to 60).forEach { (t, p) -> drain.add(t, p) }
        assertEquals(10.0, drain.percentPerMinute()!!, 1e-9)
        // Four more flat samples push the steep ones out.
        listOf(240_000L, 300_000L, 360_000L, 420_000L).forEach { drain.add(it, 60) }
        assertEquals(0.0, drain.percentPerMinute()!!, 1e-9)
    }

    @Test
    fun thermalForecastInterpolates() {
        assertEquals(0, HealthForecast.minutesToSevere(1.0f, 1.2f, 60))
        assertNull(HealthForecast.minutesToSevere(0.5f, 0.5f, 60))
        assertNull(HealthForecast.minutesToSevere(0.5f, 0.45f, 60))
        assertNull(HealthForecast.minutesToSevere(0.5f, Float.NaN, 60))
        assertNull(HealthForecast.minutesToSevere(Float.NaN, 1.5f, 60))
        // +0.1 per minute from 0.8: 1.0 in 2 min.
        assertEquals(2, HealthForecast.minutesToSevere(0.8f, 0.9f, 60))
        // +0.05 per minute from 0.7: 6 min, beyond the 5-minute horizon.
        assertNull(HealthForecast.minutesToSevere(0.7f, 0.75f, 60))
        // Within the platform's own minute: rounded up to 1.
        assertEquals(1, HealthForecast.minutesToSevere(0.9f, 1.3f, 60))
    }

    @Test
    fun batteryAlertWithHysteresisAndEscalation() {
        val f = HealthForecast()
        assertEquals(emptyList<HealthAlert>(), f.evaluate(11, false, null, cool, null))
        assertEquals(listOf(HealthAlert(HealthKind.BATTERY, AlertLevel.WARNING, 8)), f.evaluate(8, false, null, cool, null))
        // 11 min: inside the hysteresis band, still shown.
        assertEquals(1, f.evaluate(11, false, null, cool, null).size)
        assertEquals(emptyList<HealthAlert>(), f.evaluate(12, false, null, cool, null))
        assertEquals(AlertLevel.CRITICAL, f.evaluate(1, false, null, cool, null).single().level)
        // Plugged in: gone.
        assertEquals(emptyList<HealthAlert>(), f.evaluate(1, true, null, cool, null))
    }

    @Test
    fun storageAlert() {
        val f = HealthForecast()
        assertEquals(listOf(HealthAlert(HealthKind.STORAGE, AlertLevel.WARNING, 6)), f.evaluate(null, false, 6, cool, null))
        assertEquals(AlertLevel.CRITICAL, f.evaluate(null, false, 1, cool, null).single().level)
    }

    @Test
    fun thermalAlertFromASustainedForecastOnAWarmPhoneAndHold() {
        val f = HealthForecast()
        val warm = 0.85f
        // The forecast must hold for THERMAL_SUSTAIN evaluations before the alert shows.
        repeat(HealthForecast.THERMAL_SUSTAIN - 1) { assertEquals(emptyList<HealthAlert>(), f.evaluate(null, false, null, cool, 3, warm)) }
        assertEquals(listOf(HealthAlert(HealthKind.THERMAL, AlertLevel.WARNING, 3)), f.evaluate(null, false, null, cool, 3, warm))
        // The forecast goes quiet on a still warm phone: held for THERMAL_HOLD evaluations, then gone.
        repeat(HealthForecast.THERMAL_HOLD - 1) { assertEquals(1, f.evaluate(null, false, null, cool, null, warm).size) }
        assertEquals(emptyList<HealthAlert>(), f.evaluate(null, false, null, cool, null, warm))
        // SEVERE now is critical, minutes 0.
        assertEquals(HealthAlert(HealthKind.THERMAL, AlertLevel.CRITICAL, 0), f.evaluate(null, false, null, PowerManager.THERMAL_STATUS_SEVERE, null).single())
    }

    @Test
    fun noThermalAlertFromANoisyForecastOnACoolPhone() {
        // Headroom 0.5 (a phone at 28 °C) with a forecast that "reaches" SEVERE in 5 min: no alert, however long.
        val f = HealthForecast()
        repeat(HealthForecast.THERMAL_SUSTAIN * 4) { assertEquals(emptyList<HealthAlert>(), f.evaluate(null, false, null, cool, 5, 0.5f)) }
        // Nor with the headroom unknown.
        repeat(HealthForecast.THERMAL_SUSTAIN * 2) { assertEquals(emptyList<HealthAlert>(), f.evaluate(null, false, null, cool, 5)) }
    }

    @Test
    fun aBriefWarmForecastDoesNotWarnAndACooledPhoneClearsAtOnce() {
        val f = HealthForecast()
        // One quiet sample resets the streak.
        repeat(HealthForecast.THERMAL_SUSTAIN - 1) { f.evaluate(null, false, null, cool, 4, 0.85f) }
        assertEquals(emptyList<HealthAlert>(), f.evaluate(null, false, null, cool, null, 0.85f))
        repeat(HealthForecast.THERMAL_SUSTAIN - 1) { assertEquals(emptyList<HealthAlert>(), f.evaluate(null, false, null, cool, 4, 0.85f)) }
        assertEquals(1, f.evaluate(null, false, null, cool, 4, 0.85f).size)
        // The headroom falls under CLEAR_HEADROOM: the alert ends without waiting for the hold.
        assertEquals(emptyList<HealthAlert>(), f.evaluate(null, false, null, cool, null, 0.7f))
    }

    @Test
    fun sameListWhileNothingChanges() {
        val f = HealthForecast()
        val first = f.evaluate(5, false, 5, cool, null)
        assertSame(first, f.evaluate(5, false, 5, cool, null))
    }

    @Test
    fun healthMapsToTheWire() {
        val health = HealthState(
            batteryPercent = 40,
            batteryMinutesLeft = 8,
            batteryTempTenthsC = 385,
            thermalStatus = PowerManager.THERMAL_STATUS_MODERATE,
            thermalHeadroom = 0.73f,
            storageMinutesLeft = 120,
            freeBytes = 41_200_000_000,
            alerts = listOf(HealthAlert(HealthKind.BATTERY, AlertLevel.WARNING, 8))
        )
        val wire = health.toDeviceHealth()
        assertEquals(40, wire.batteryPercent)
        assertEquals(ThermalLevel.MODERATE, wire.thermal)
        assertEquals(120, wire.freeSpaceMinutes)
        val detail = wire.detail!!
        assertEquals(73, detail.thermalHeadroomPercent)
        assertEquals(41_200L, detail.freeMegabytes)
        assertEquals(385, detail.batteryTempTenthsC)
        assertEquals(HealthAlertKind.BATTERY, detail.alerts.single().kind)
        assertNull(HealthState().toDeviceHealth().batteryPercent)
    }

    @Test
    fun gigabytesWithOneDecimal() {
        assertEquals("41.2", formatGb(41_234_000_000))
        assertEquals("0.0", formatGb(0))
    }
}
