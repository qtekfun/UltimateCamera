package com.qtekfun.ultimatecamera.core.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInputKindTest {
    @Test
    fun preselectionOrderIsUsbThenBluetoothThenInternal() {
        val devices = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_TELEPHONY,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BUILTIN_MIC
        )
        val ordered = preferredOrder(devices) { it }
        assertEquals(
            listOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_TELEPHONY
            ),
            ordered
        )
    }

    @Test
    fun bleAndScoNeedTheCommunicationRoute() {
        assertEquals(true, AudioInputKind.needsCommunicationRoute(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(false, AudioInputKind.needsCommunicationRoute(AudioDeviceInfo.TYPE_USB_HEADSET))
    }
}
