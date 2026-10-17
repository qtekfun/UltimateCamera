package com.qtekfun.ultimatecamera.feature.multicam.pairing

import android.content.Context
import com.qtekfun.ultimatecamera.core.net.control.Session
import com.qtekfun.ultimatecamera.core.net.pairing.B64
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevice
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevices
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevicesFile
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevicesLoad
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phones this one is paired with, and its own device id (REQ-NET-002). One per process ([getInstance]); every
 * change is saved at once through the [PairedDevicesVault]. Thread-safe; file access happens on the caller's thread,
 * so call it off the main thread.
 */
class PairedDevicesRepository(private val vault: PairedDevicesVault, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var file: PairedDevicesFile
    private val _devices: MutableStateFlow<List<PairedDevice>>

    /** Why the stored list could not be read (it was reset), or null. */
    val loadProblem: String?

    init {
        val loaded = vault.load()
        file = loaded.file
        loadProblem = (loaded as? PairedDevicesLoad.Recovered)?.reason
        // A new own id must survive a restart, or peers would see a different phone.
        if ((loaded is PairedDevicesLoad.Ok && loaded.created) || loaded is PairedDevicesLoad.Recovered) persist()
        _devices = MutableStateFlow(file.devices)
    }

    val ownId: String get() = synchronized(lock) { file.ownId }

    val devices: StateFlow<List<PairedDevice>> get() = _devices.asStateFlow()

    /** False when Keystore failed and the keys sit in the plain fallback file. */
    val keysProtected: Boolean get() = vault.protected

    fun pairKey(deviceId: String): ByteArray? = synchronized(lock) { file.devices.firstOrNull { it.id == deviceId }?.keyBytes() }

    fun isPaired(deviceId: String): Boolean = synchronized(lock) { file.devices.any { it.id == deviceId } }

    /** Stores the pair key of a session opened with a pairing code; [endpoints] are where to dial it later. */
    fun addFromSession(session: Session, endpoints: List<Endpoint> = emptyList()): PairedDevice? {
        val key = session.newPairKey ?: return null
        val now = nowMs()
        val device = PairedDevice(
            id = session.peerId,
            name = session.peerName,
            role = session.peerRole,
            key = B64.encode(key),
            endpoints = endpoints,
            pairedAtEpochMs = now,
            lastSeenEpochMs = now
        )
        update { PairedDevices.upsert(it, device) }
        return device
    }

    fun remove(deviceId: String) = update { PairedDevices.remove(it, deviceId) }

    /** Records that [deviceId] was seen now (discovery or a connection), with fresh [endpoints] when known. */
    fun touch(deviceId: String, endpoints: List<Endpoint>? = null) = update { PairedDevices.touch(it, deviceId, endpoints, nowMs()) }

    private fun update(edit: (PairedDevicesFile) -> PairedDevicesFile) = synchronized(lock) {
        val next = edit(file)
        if (next == file) return@synchronized
        file = next
        persist()
        _devices.value = next.devices
    }

    private fun persist() {
        try {
            vault.save(file)
        } catch (_: IOException) {
            // Kept in memory for this session; the next change retries the write.
        } catch (_: IllegalStateException) {
            // Written by a newer app version: used read-only.
        }
    }

    companion object {
        @Volatile
        private var instance: PairedDevicesRepository? = null

        /** The process-wide repository (first call reads the file and may create the Keystore key: not on the main thread). */
        fun getInstance(context: Context): PairedDevicesRepository = instance ?: synchronized(this) {
            instance ?: PairedDevicesRepository(PairedDevicesVault(context.applicationContext.filesDir, KeystoreProtector.createOrNull()))
                .also { instance = it }
        }
    }
}
