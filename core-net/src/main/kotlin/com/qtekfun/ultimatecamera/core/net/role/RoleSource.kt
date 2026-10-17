package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The multicamera role this phone plays, as the camera screen reads it (D56). The role choice and the pairing screen
 * (feature-multicam) provide the real one through [RoleSources.current]; until then every phone is standalone.
 */
interface RoleSource {
    /** [DeviceRole.CAMERA] turns the camera role on; anything else (or null: standalone) keeps it off. */
    val role: Flow<DeviceRole?>

    /** Token of the pairing QR code shown now (first connection of a monitor), or null when none is shown. */
    fun pairingToken(): ByteArray? = null

    /** Name other phones see in discovery and in the handshake. */
    fun deviceName(): String? = null

    /** The paired-devices store to use (feature-multicam: Keystore-sealed, D55); null keeps the camera's plain file. */
    fun pairings(): PairingStore? = null
}

/** This phone's id and its pair keys, as the camera role needs them. Calls may touch storage: not on the main thread. */
interface PairingStore {
    val ownId: String

    fun pairKey(deviceId: String): ByteArray?

    /** Stores the new pair key of a session opened with a pairing code. */
    fun store(session: Session)
}

/** Where the camera screen finds the [RoleSource]. Set once at app start, before the camera screen is shown. */
object RoleSources {
    /** No multicamera: the camera role stays off. */
    object Standalone : RoleSource {
        override val role: Flow<DeviceRole?> = flowOf(null)
    }

    @Volatile var current: RoleSource = Standalone
}
