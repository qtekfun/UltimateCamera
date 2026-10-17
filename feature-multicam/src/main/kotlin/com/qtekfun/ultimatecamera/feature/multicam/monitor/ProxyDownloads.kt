package com.qtekfun.ultimatecamera.feature.multicam.monitor

import com.qtekfun.ultimatecamera.core.net.control.ClientHandshake
import com.qtekfun.ultimatecamera.core.net.control.HandshakeException
import com.qtekfun.ultimatecamera.core.net.control.HandshakeMode
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.transfer.DownloadResult
import com.qtekfun.ultimatecamera.core.net.transfer.ProxyEntry
import com.qtekfun.ultimatecamera.core.net.transfer.ProxyTransferClient
import com.qtekfun.ultimatecamera.core.net.transfer.ProxyTransferProtocol
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where one of the camera's proxies stands on this phone. */
enum class ProxyItemState { REMOTE, PARTIAL, DOWNLOADING, PAUSED, DONE, CORRUPT, FAILED }

data class ProxyItem(val name: String, val bytes: Long, val held: Long, val state: ProxyItemState) {
    /** 0..1 of the file held here. */
    val progress: Float get() = if (bytes > 0) (held.toFloat() / bytes).coerceIn(0f, 1f) else 0f
}

data class ProxyListUi(val loading: Boolean = false, val items: List<ProxyItem> = emptyList(), val unreachable: Boolean = false)

/** Where a monitor keeps a camera's proxies: the app's own Movies folder (no permission), one folder per camera. */
object ProxyFolder {
    fun forCamera(context: android.content.Context, cameraName: String): File {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(File(base, "proxies"), folderName(cameraName))
    }

    /** [cameraName] reduced to safe file-name characters (never empty). */
    fun folderName(cameraName: String): String =
        cameraName.map { if (it.isLetterOrDigit() || it in "_-") it else '_' }.joinToString("").take(MAX_CHARS).ifBlank { "camera" }

    private const val MAX_CHARS = 40
}

/** Pure state rules of [ProxyDownloads] (unit-tested). */
object ProxyListMath {
    /** The state of a listed proxy from what is on disk: nothing, part of it, or (once verified) all of it. */
    fun stateOf(entry: ProxyEntry, held: Long, verified: Boolean): ProxyItemState = when {
        held <= 0 -> ProxyItemState.REMOTE
        verified && held == entry.bytes -> ProxyItemState.DONE
        else -> ProxyItemState.PARTIAL
    }

    fun stateOf(result: DownloadResult): ProxyItemState = when (result) {
        is DownloadResult.Complete -> ProxyItemState.DONE
        is DownloadResult.Paused -> ProxyItemState.PAUSED
        DownloadResult.Corrupt -> ProxyItemState.CORRUPT
        is DownloadResult.Failed -> ProxyItemState.FAILED
    }

    /** Only names a camera could have produced reach the disk: no path separators or parent references. */
    fun safeName(name: String): Boolean = name.isNotBlank() && name.all { it.isLetterOrDigit() || it in "_-." } && !name.startsWith(".")
}

/**
 * The monitor's "Proxies" list for one camera (REQ-NET-006, D59): lists the proxies the camera offers and downloads them
 * into [dir] over the camera's authenticated proxy socket ([ProxyTransferProtocol.DEFAULT_PORT]), resuming partial files
 * and verifying the SHA-256. Files verified in this session are remembered; a partial file is resumed on the next tap.
 */
class ProxyDownloads(
    private val target: RemoteCameraTarget,
    private val local: LocalIdentity,
    private val dir: File,
    private val port: Int = ProxyTransferProtocol.DEFAULT_PORT
) {
    private val _state = MutableStateFlow(ProxyListUi())
    val state: StateFlow<ProxyListUi> = _state.asStateFlow()
    private val verified = mutableSetOf<String>()
    private val lock = Mutex()
    private var job: Job? = null

    fun refresh(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            lock.withLock {
                _state.update { it.copy(loading = true, unreachable = false) }
                val entries = withClient { it.list() }
                _state.value = if (entries == null) {
                    _state.value.copy(loading = false, unreachable = true)
                } else {
                    ProxyListUi(items = entries.filter { ProxyListMath.safeName(it.name) }.map(::itemOf))
                }
            }
        }
    }

    fun download(scope: CoroutineScope, name: String) {
        if (job?.isActive == true || !ProxyListMath.safeName(name)) return
        job = scope.launch {
            lock.withLock {
                setItem(name) { it.copy(state = ProxyItemState.DOWNLOADING) }
                val file = File(dir, name)
                val result = withClient { client ->
                    dir.mkdirs()
                    client.download(name, file) { held -> setItem(name) { it.copy(held = held) } }
                } ?: DownloadResult.Failed("unreachable")
                if (result is DownloadResult.Complete) verified += name
                setItem(name) { it.copy(held = if (file.exists()) file.length() else 0, state = ProxyListMath.stateOf(result)) }
            }
        }
    }

    private fun itemOf(entry: ProxyEntry): ProxyItem {
        val held = File(dir, entry.name).let { if (it.exists()) it.length() else 0L }
        return ProxyItem(entry.name, entry.bytes, held, ProxyListMath.stateOf(entry, held, entry.name in verified))
    }

    private fun setItem(name: String, change: (ProxyItem) -> ProxyItem) =
        _state.update { ui -> ui.copy(items = ui.items.map { if (it.name == name) change(it) else it }) }

    /** Connects to the first reachable endpoint of the camera, runs [block] and closes; null when none answered. */
    private suspend fun <T> withClient(block: (ProxyTransferClient) -> T): T? = withContext(Dispatchers.IO) {
        for (endpoint in target.endpoints()) {
            val client = try {
                ProxyTransferClient.connect(endpoint.host, port, ClientHandshake(local, HandshakeMode.RESUME, target.id, target.pairKey))
            } catch (_: IOException) {
                continue
            } catch (_: HandshakeException) {
                return@withContext null
            }
            return@withContext try {
                client.use(block)
            } catch (_: IOException) {
                null
            }
        }
        null
    }
}
