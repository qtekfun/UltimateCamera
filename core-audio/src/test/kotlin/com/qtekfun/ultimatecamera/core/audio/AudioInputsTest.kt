package com.qtekfun.ultimatecamera.core.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputsTest {
    @Test
    fun onlyInputsThatCanCarryATakeAreListed() {
        listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        ).forEach { assertTrue(AudioInputKind.typeName(it), isRecordable(it)) }
        listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
            AudioDeviceInfo.TYPE_FM_TUNER
        ).forEach { assertFalse(AudioInputKind.typeName(it), isRecordable(it)) }
    }

    @Test
    fun builtInMicsAreToldApartByTheirAddress() {
        val bottom = audioSourceOf(7, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 8", "bottom")
        val back = audioSourceOf(8, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 8", "back")
        assertEquals("Pixel 8 (bottom)", bottom.name)
        assertEquals(AudioInputKind.BUILTIN, bottom.kind)
        assertEquals(7, bottom.id)
        assertTrue(bottom.key != back.key)
        assertEquals(AudioInputChoice.key(AudioInputKind.BUILTIN, "Pixel 8", "bottom"), bottom.key)
    }

    @Test
    fun externalInputsKeepTheProductNameAndIgnoreTheAddress() {
        val usb = audioSourceOf(3, AudioDeviceInfo.TYPE_USB_DEVICE, "RODE Wireless GO II", "card=1;device=0")
        assertEquals("RODE Wireless GO II", usb.name)
        assertEquals(AudioInputChoice.key(AudioInputKind.USB, "RODE Wireless GO II"), usb.key)
        assertFalse(usb.callQuality)
    }

    @Test
    fun aNamelessDeviceIsNamedAfterItsTypeAndBluetoothCallRoutesAreFlagged() {
        val sco = audioSourceOf(5, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, " ", null)
        assertEquals("BLUETOOTH_SCO", sco.name)
        assertEquals(AudioInputKind.BLUETOOTH, sco.kind)
        assertTrue(sco.callQuality)
        assertEquals("BUILTIN_MIC", audioSourceOf(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, null, "").name)
    }
}
