package com.qtekfun.ultimatecamera.core.audio

/**
 * Which input a take records from (REQ-AUD-001), from the devices present and the user's stored pick.
 *
 * Android's `AudioDeviceInfo.getId()` changes every time a device is plugged in again, so a pick is stored as a stable
 * [key] (kind, product name and, for built-in microphones, the address that tells the bottom mic from the back one).
 * A stored pick whose device is absent falls back to the automatic choice of [AudioSourceSelection]
 * (USB > Bluetooth > wired > built-in) without forgetting the pick, so it is used again when the device returns.
 */
object AudioInputChoice {
    private const val SEPARATOR = ":"

    /** Stable key of an input. USB and Bluetooth addresses change per connection, so only built-in mics use theirs. */
    fun key(kind: AudioInputKind, productName: String, address: String = ""): String = buildString {
        append(kind.name).append(SEPARATOR).append(productName.trim())
        if (kind == AudioInputKind.BUILTIN && address.isNotBlank()) append(SEPARATOR).append(address.trim())
    }

    /** The device to record from: the stored pick if present, else the best one; null when there is no input. */
    fun resolve(available: List<AudioSource>, storedKey: String?): AudioSource? {
        val automatic = AudioSourceSelection().onDevicesChanged(available)
        val picked = storedKey?.let { k -> available.firstOrNull { it.key == k } } ?: return automatic.selected
        return automatic.select(picked.id).selected
    }

    /** True when [storedKey] names a device that is not connected now (the UI says the automatic choice is used). */
    fun isMissing(available: List<AudioSource>, storedKey: String?): Boolean = storedKey != null && available.none { it.key == storedKey }
}
