package com.qtekfun.ultimatecamera.feature.multicam.roles

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.Session
import com.qtekfun.ultimatecamera.core.net.pairing.PairingPayload
import com.qtekfun.ultimatecamera.core.net.role.PairingStore
import com.qtekfun.ultimatecamera.core.net.role.RoleSource
import com.qtekfun.ultimatecamera.core.settings.MulticamRole
import com.qtekfun.ultimatecamera.core.settings.MulticamRoleRepository
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairedDevicesRepository
import com.qtekfun.ultimatecamera.feature.multicam.pairing.PairingWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The [RoleSource] the camera screen reads (D55, D56): the persisted role, the one-use token of the code shown on the
 * pairing screen, this phone's name and the Keystore-sealed paired devices. The app sets it in `RoleSources.current`
 * at start.
 */
class MulticamRoleSource(context: Context, roles: MulticamRoleRepository) : RoleSource {
    private val app = context.applicationContext

    override val role: Flow<DeviceRole?> = roles.multicamRole.map { it?.toDeviceRole() }

    override fun pairingToken(): ByteArray? = PairingWindow.shared.takeToken()

    override fun deviceName(): String = deviceName(app)

    override fun pairings(): PairingStore = object : PairingStore {
        private val repository by lazy { PairedDevicesRepository.getInstance(app) }

        override val ownId: String get() = repository.ownId

        override fun pairKey(deviceId: String): ByteArray? = repository.pairKey(deviceId)

        override fun store(session: Session) {
            repository.addFromSession(session)
        }
    }

    companion object {
        /** The user-visible device name (Settings › About), else the model; at most the QR's name length. */
        fun deviceName(context: Context): String = (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL)
            .trim()
            .ifEmpty { "UltimateCamera" }
            .take(PairingPayload.MAX_NAME_CHARS)
    }
}

/** The persisted role as the control protocol's role. */
fun MulticamRole.toDeviceRole(): DeviceRole = when (this) {
    MulticamRole.CAMERA -> DeviceRole.CAMERA
    MulticamRole.MONITOR -> DeviceRole.MONITOR
    MulticamRole.MASTER -> DeviceRole.MASTER
}
