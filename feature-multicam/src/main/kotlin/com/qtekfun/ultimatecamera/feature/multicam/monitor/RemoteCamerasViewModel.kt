package com.qtekfun.ultimatecamera.feature.multicam.monitor

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.feature.camera.AlertLevel
import com.qtekfun.ultimatecamera.feature.camera.HealthKind
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Remembers which critical alerts were already signalled, so each camera vibrates once per kind when it turns critical
 * (like the camera itself, D52) and again only after it cleared. Pure, unit-tested.
 */
class CriticalAlertTracker {
    private var signalled: Set<Pair<String, HealthKind>> = emptySet()

    /** True when [cameras] hold a critical alert that was not critical at the previous call. */
    fun update(cameras: List<RemoteCameraUi>): Boolean {
        val now = cameras.flatMap { c -> c.alerts.filter { it.level == AlertLevel.CRITICAL }.map { c.id to it.kind } }.toSet()
        val fresh = now - signalled
        signalled = now
        return fresh.isNotEmpty()
    }
}

/**
 * Video in the master's tiles (D57): only while thumbnails are on, for at most [MAX_VIDEO_TILES] cameras, only while
 * the control link is live, and not when its round trip says the network is short (status-only tile instead). A tile
 * whose stream is down meanwhile shows its status only and keeps retrying the video.
 */
object TileVideoPolicy {
    const val MAX_VIDEO_TILES = 4
    const val SHORT_NETWORK_RTT_NANOS = 150_000_000L

    fun showVideo(thumbnailsOn: Boolean, index: Int, ui: RemoteCameraUi, rttNanos: Long?): Boolean =
        thumbnailsOn && index < MAX_VIDEO_TILES && ui.link == LinkUi.LIVE && (rttNanos ?: 0L) < SHORT_NETWORK_RTT_NANOS
}

/**
 * The cameras this phone controls (one for a monitor, up to [MAX_CAMERAS] for the master), shared by the monitor and
 * master screens so opening a tile's monitor reuses the same control link.
 *
 * @param commonNow the common clock now (ns) when this phone has one (it is the clock source, or synced), else null.
 * @param vibrate one short vibration (a critical alert just appeared on a camera).
 */
class RemoteCamerasViewModel(
    targets: List<RemoteCameraTarget>,
    local: LocalIdentity,
    private val syncedStart: SyncedStart?,
    private val commonNow: () -> Long?,
    private val vibrate: () -> Unit
) : ViewModel() {
    val cameras: List<RemoteCamera> = targets.take(MAX_CAMERAS).map { RemoteCamera(it, local) }
    private val tracker = CriticalAlertTracker()
    private val tick = MutableStateFlow(0L)
    private val _ui = MutableStateFlow(cameras.map { RemoteCameraMapper.map(it.snapshot.value) })
    val ui: StateFlow<List<RemoteCameraUi>> = _ui.asStateFlow()

    /** Master tiles: video thumbnails on (default on for one or two cameras, status-only above). */
    private val _thumbnails = MutableStateFlow(cameras.size <= 2)
    val thumbnails: StateFlow<Boolean> = _thumbnails.asStateFlow()

    init {
        cameras.forEach { it.start(viewModelScope) }
        viewModelScope.launch {
            combine(cameras.map { it.snapshot } + tick) { it }.collect { refresh() }
        }
        viewModelScope.launch {
            while (true) {
                val recording = _ui.value.any { it.rec == RecUi.RECORDING }
                delay(if (recording) TIMECODE_TICK_MS else IDLE_TICK_MS)
                tick.value = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun refresh() {
        val now = commonNow()
        val nowMs = SystemClock.elapsedRealtime()
        val mapped = cameras.map { RemoteCameraMapper.map(it.snapshot.value, now, it.recordingSeenSinceMs, nowMs) }
        _ui.value = mapped
        if (tracker.update(mapped)) vibrate()
    }

    fun camera(id: String): RemoteCamera? = cameras.firstOrNull { it.id == id }

    fun rtt(id: String): Long? = camera(id)?.snapshot?.value?.rttNanos

    fun toggleRecording(id: String) {
        val c = camera(id) ?: return
        val plan = GroupStartPlanner.plan(syncedStart, listOfNotNull(c.snapshot.value.rttNanos), UUID.randomUUID().toString())
        c.toggleRecording(viewModelScope, plan.startAtCommonNanos, plan.recordingId)
    }

    fun send(id: String, body: CommandBody) {
        camera(id)?.send(viewModelScope, body)
    }

    fun clearError(id: String) {
        camera(id)?.clearError()
    }

    /** REC on every camera with one recording id, at a common time when the synced-start service can plan one. */
    fun recordAll() {
        val idle = cameras.filterNot { RemoteCameraMapper.recUi(it.snapshot.value.status?.recording, it.snapshot.value.recIntent).showsRecording }
        if (idle.isEmpty()) return
        val plan = GroupStartPlanner.plan(syncedStart, cameras.mapNotNull { it.snapshot.value.rttNanos }, UUID.randomUUID().toString())
        idle.forEach { it.send(viewModelScope, CommandBody.StartRecording(plan.recordingId, plan.startAtCommonNanos)) }
    }

    fun stopAll() = cameras.forEach { it.send(viewModelScope, CommandBody.StopRecording(it.snapshot.value.status?.recordingId)) }

    fun setThumbnails(on: Boolean) {
        _thumbnails.value = on
    }

    override fun onCleared() {
        cameras.forEach { it.stop() }
    }

    companion object {
        const val MAX_CAMERAS = 4
        private const val TIMECODE_TICK_MS = 100L
        private const val IDLE_TICK_MS = 1_000L

        fun factory(
            targets: List<RemoteCameraTarget>,
            local: LocalIdentity,
            syncedStart: SyncedStart? = null,
            commonNow: () -> Long? = { null },
            vibrate: () -> Unit = {}
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RemoteCamerasViewModel(targets, local, syncedStart, commonNow, vibrate) }
        }
    }
}
