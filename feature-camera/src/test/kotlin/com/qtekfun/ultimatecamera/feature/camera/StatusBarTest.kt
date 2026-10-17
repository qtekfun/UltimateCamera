package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusBarTest {
    @Test
    fun thermalLabelsByLevel() {
        assertEquals(R.string.status_thermal_normal, thermalLabel(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(R.string.status_thermal_moderate, thermalLabel(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(R.string.status_thermal_severe, thermalLabel(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(R.string.status_thermal_critical, thermalLabel(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun numbersFormatWithOneDecimal() {
        assertEquals("98.4", formatMbps(98_400_000))
        assertEquals("0.0", formatMbps(0))
        assertEquals("12.3", formatTenths(123))
        assertEquals("0.7", formatTenths(7))
    }

    @Test
    fun volumeKeysToggleOnFirstPressOnly() {
        assertEquals(VolumeKeyAction.TOGGLE, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0))
        assertEquals(VolumeKeyAction.TOGGLE, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0))
        assertEquals(VolumeKeyAction.CONSUME, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 3))
        assertEquals(VolumeKeyAction.CONSUME, VolumeKeys.action(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, 0))
        assertEquals(VolumeKeyAction.PASS, VolumeKeys.action(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, 0))
    }
}
