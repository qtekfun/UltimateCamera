package com.qtekfun.ultimatecamera.core.audio

import android.media.AudioDeviceInfo

/** Families of input devices, in the order the source selector prefers them (REQ-AUD-001: USB > Bluetooth > internal). */
enum class AudioInputKind(val priority: Int) {
    USB(0),
    BLUETOOTH(1),
    WIRED(2),
    BUILTIN(3),
    OTHER(4)
    ;

    companion object {
        fun of(type: Int): AudioInputKind = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> USB
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> WIRED
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> BUILTIN
            else -> OTHER
        }

        /** Bluetooth inputs that only work as a call-style route (`setCommunicationDevice`), with reduced quality. */
        fun needsCommunicationRoute(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

        fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
            else -> "TYPE_$type"
        }
    }
}

/** Orders candidate inputs for preselection: by [AudioInputKind] priority, then by the order the system listed them. */
fun <T> preferredOrder(devices: List<T>, typeOf: (T) -> Int): List<T> =
    devices.withIndex().sortedWith(compareBy({ AudioInputKind.of(typeOf(it.value)).priority }, { it.index })).map { it.value }
