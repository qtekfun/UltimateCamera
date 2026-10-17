package com.qtekfun.ultimatecamera.feature.camera.remote

import android.content.Context
import android.net.Uri
import android.util.Log
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.transfer.ProxyEntry
import com.qtekfun.ultimatecamera.core.net.transfer.ProxySource
import com.qtekfun.ultimatecamera.core.net.transfer.ProxyTransferProtocol
import com.qtekfun.ultimatecamera.core.net.transfer.ProxyTransferServer
import com.qtekfun.ultimatecamera.core.recording.ProxyRegistry
import java.io.IOException
import java.io.InputStream

/**
 * The camera role's proxy upload (REQ-NET-006, D59): serves the proxies recorded since the app started
 * ([ProxyRegistry]) to paired controllers on [ProxyTransferProtocol.DEFAULT_PORT], paused while [recording].
 */
internal object ProxyUpload {
    /** Runs until cancelled; a port that cannot be bound only costs the uploads (logged). */
    suspend fun serve(context: Context, local: LocalIdentity, keyring: Keyring, recording: () -> Boolean) {
        // RESUME only: pairing happens on the control port, never on the data socket.
        val resumeOnly = object : Keyring {
            override fun pairKey(deviceId: String): ByteArray? = keyring.pairKey(deviceId)

            override fun pairingToken(): ByteArray? = null

            override fun accepts(role: com.qtekfun.ultimatecamera.core.net.control.DeviceRole): Boolean = keyring.accepts(role)
        }
        try {
            ProxyTransferServer(Source(context, recording)).serve(ProxyTransferProtocol.DEFAULT_PORT, { ServerHandshake(local, resumeOnly) })
        } catch (e: IOException) {
            Log.w(TAG, "proxy upload unavailable: ${e.message}")
        }
    }

    private class Source(private val context: Context, private val recording: () -> Boolean) : ProxySource {
        override fun list(): List<ProxyEntry> = ProxyRegistry.all.map { ProxyEntry(it.name, it.bytes) }

        override fun open(name: String): InputStream? {
            val file = ProxyRegistry.all.firstOrNull { it.name == name } ?: return null
            return context.contentResolver.openInputStream(Uri.parse(file.uri))
        }

        override val busy: Boolean get() = recording()
    }

    private const val TAG = "ProxyUpload"
}
