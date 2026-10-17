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
        assertEquals(true, AudioInputKind.needsCommunicationRoute(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(false, AudioInputKind.needsCommunicationRoute(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
    }

    @Test
    fun everyInputTypeFallsInItsFamily() {
        val expected = mapOf(
            AudioDeviceInfo.TYPE_USB_DEVICE to AudioInputKind.USB,
            AudioDeviceInfo.TYPE_USB_HEADSET to AudioInputKind.USB,
            AudioDeviceInfo.TYPE_USB_ACCESSORY to AudioInputKind.USB,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to AudioInputKind.BLUETOOTH,
            AudioDeviceInfo.TYPE_BLE_HEADSET to AudioInputKind.BLUETOOTH,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to AudioInputKind.BLUETOOTH,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to AudioInputKind.WIRED,
            AudioDeviceInfo.TYPE_LINE_ANALOG to AudioInputKind.WIRED,
            AudioDeviceInfo.TYPE_LINE_DIGITAL to AudioInputKind.WIRED,
            AudioDeviceInfo.TYPE_BUILTIN_MIC to AudioInputKind.BUILTIN,
            AudioDeviceInfo.TYPE_TELEPHONY to AudioInputKind.OTHER,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX to AudioInputKind.OTHER
        )
        expected.forEach { (type, kind) -> assertEquals(AudioInputKind.typeName(type), kind, AudioInputKind.of(type)) }
        assertEquals(listOf(0, 1, 2, 3, 4), AudioInputKind.entries.map { it.priority })
    }

    @Test
    fun typeNamesAreReadableAndUnknownTypesKeepTheirNumber() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
            AudioDeviceInfo.TYPE_FM_TUNER
        )
        val names = types.map(AudioInputKind::typeName)
        assertEquals(types.size, names.toSet().size)
        assertEquals("BUILTIN_MIC", names.first())
        assertEquals("FM_TUNER", names.last())
        assertEquals(false, names.any { it.startsWith("TYPE_") })
        assertEquals("TYPE_9999", AudioInputKind.typeName(9999))
    }
}
