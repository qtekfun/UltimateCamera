package com.qtekfun.ultimatecamera.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The phone's recording inputs as [AudioSource]s (thin glue over `AudioManager`; the choice is [AudioInputChoice]).
 * Inputs that cannot carry a take (telephony, remote submix, FM tuner, A2DP which is output-only) are left out.
 */
class AudioInputs(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    /** The usable inputs, best first (USB > Bluetooth > wired > built-in). */
    fun list(): List<AudioSource> = devices().map(::toSource)

    /** The platform device behind [source], or null if it was disconnected. */
    fun device(source: AudioSource): AudioDeviceInfo? = devices().firstOrNull { it.id == source.id }

    /** Emits the current inputs, then again on every connection or disconnection. */
    fun changes(): Flow<List<AudioSource>> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                trySend(list())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                trySend(list())
            }
        }
        // The callback fires once at registration with the current devices.
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    private fun devices(): List<AudioDeviceInfo> = preferredOrder(
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource && isRecordable(it.type) }
    ) { it.type }

    private fun toSource(device: AudioDeviceInfo): AudioSource {
        val kind = AudioInputKind.of(device.type)
        val product = device.productName?.toString().orEmpty().ifBlank { AudioInputKind.typeName(device.type) }
        val address = device.address.orEmpty()
        // Phones list each built-in mic (bottom, back, top) under the phone's name; the address tells them apart.
        val name = if (kind == AudioInputKind.BUILTIN && address.isNotBlank()) "$product ($address)" else product
        return AudioSource(
            id = device.id,
            kind = kind,
            name = name,
            key = AudioInputChoice.key(kind, product, address),
            callQuality = AudioInputKind.needsCommunicationRoute(device.type)
        )
    }

    private fun isRecordable(type: Int): Boolean = AudioInputKind.of(type) != AudioInputKind.OTHER && type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
}
