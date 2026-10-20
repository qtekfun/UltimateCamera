package com.qtekfun.ultimatecamera.feature.camera.remote

import android.content.Context
import android.util.Log
import com.qtekfun.ultimatecamera.core.gpu.gl.StreamOutput
import com.qtekfun.ultimatecamera.core.net.monitor.MonitorLinkState
import com.qtekfun.ultimatecamera.core.net.monitor.MonitorSender
import com.qtekfun.ultimatecamera.core.net.monitor.MonitorVideoConfig
import com.qtekfun.ultimatecamera.core.net.monitor.QueueSignallingTransport
import com.qtekfun.ultimatecamera.core.net.role.CameraNode
import com.qtekfun.ultimatecamera.core.net.role.VideoSignalHub
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.RecordingProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * The monitored image to one controller at a time over WebRTC (REQ-NET-004, D70). Signalling travels inside the
 * authenticated control channel ([hub], fed by the [CameraNode]); the plain socket on 47801 is opened only while an
 * authenticated controller without that feature (an older build) is connected, and only accepts its address.
 */
internal class CameraVideoLink(
    private val context: Context,
    private val controller: CameraController,
    private val confined: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val profile: () -> RecordingProfile?,
    private val onStreaming: (Boolean) -> Unit
) {
    @Volatile private var node: CameraNode? = null

    /** Hosts of the connected legacy controllers, refreshed by [serveLegacy]; read by the legacy accept on its IO thread. */
    @Volatile private var legacyHosts: Set<String> = emptySet()

    private val streamLock = Mutex()

    /** Signals for the node; the camera's own signals hop to the node's thread and never block libwebrtc's. */
    val hub = VideoSignalHub { peerId, message ->
        val target = node
        if (target != null) scope.launch(confined) { target.signalling.send(peerId, message) }
        target != null
    }

    /** Serves video until cancelled. [hostOf] maps a peer id to its address (for the legacy path only). */
    suspend fun run(node: CameraNode, hostOf: (peerId: String) -> String?) {
        this.node = node
        try {
            coroutineScope {
                launch { serveRequests() }
                launch { serveLegacy(node, hostOf) }
            }
        } finally {
            hub.close()
            this.node = null
        }
    }

    private suspend fun serveRequests() {
        for (request in hub.requests) {
            if (request.transport.closed.value) continue // superseded by a newer hello or the peer left
            if (!streamOnce(request.transport)) return
        }
    }

    private suspend fun serveLegacy(node: CameraNode, hostOf: (String) -> String?) = coroutineScope {
        launch {
            while (currentCoroutineContext().isActive) {
                legacyHosts = withContext(confined) { node.signalling.legacyPeers.mapNotNull(hostOf).toSet() }
                delay(LEGACY_REFRESH_MS)
            }
        }
        while (currentCoroutineContext().isActive) {
            if (legacyHosts.isNotEmpty() && !streamOnce(transport = null)) return@coroutineScope
            delay(VIDEO_RETRY_MS)
        }
    }

    /**
     * One WebRTC session until it ends: over [transport] (D70) or, when null, the legacy socket. False when libwebrtc
     * cannot be set up on this phone (no video at all then).
     */
    @Suppress("TooGenericExceptionCaught") // libwebrtc may be missing or fail to set up: the camera works without video.
    private suspend fun streamOnce(transport: QueueSignallingTransport?): Boolean {
        val profile = profile() ?: run {
            transport?.close()
            return true
        }
        // Small and thinned on modest phones: the take keeps its frame rate (D81).
        val spec = MonitorStreamSpec.of(profile.width, profile.height, controller.modestPhone)
        val width = spec.width
        val height = spec.height
        val config = MonitorVideoConfig(width = width, height = height, fps = spec.maxFps, bitrateBps = spec.bitrateBps)
        val sender = try {
            withContext(Dispatchers.Default) {
                MonitorSender(
                    context,
                    config,
                    signalling = transport,
                    legacyAllowed = { host -> host in legacyHosts },
                    rotation = { controller.streamRotation }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "no monitor video: ${e.message}")
            transport?.close()
            return false
        }
        var locked = false
        try {
            if (!startWhileWanted(sender, transport)) return true
            // One stream at a time: a second controller that connected meanwhile is turned away (it retries).
            locked = streamLock.tryLock()
            if (!locked) return true
            // Drawn only while a monitor is connected: no GPU work for an empty link.
            controller.streamOutput = StreamOutput(sender.inputSurface, width, height, 1, maxFps = spec.maxFps)
            onStreaming(true)
            val ended = sender.state.filter { it == MonitorLinkState.FAILED || it == MonitorLinkState.CLOSED }.map { }
            if (transport == null) ended.first() else merge(ended, transport.closed.filter { it }.map { }).first()
        } finally {
            withContext(NonCancellable) {
                if (locked) {
                    onStreaming(false)
                    controller.streamOutput = null
                    streamLock.unlock()
                }
                sender.close()
                transport?.close()
            }
        }
        return true
    }

    /** Starts [sender]; a legacy listener gives up once no legacy controller is connected any more. */
    private suspend fun startWhileWanted(sender: MonitorSender, transport: QueueSignallingTransport?): Boolean = coroutineScope {
        val started = async { sender.start() }
        if (transport == null) {
            while (!started.isCompleted) {
                if (legacyHosts.isEmpty() && sender.state.value == MonitorLinkState.SIGNALLING) {
                    started.cancel()
                    return@coroutineScope false
                }
                delay(LEGACY_REFRESH_MS)
            }
        }
        started.await()
    }

    private companion object {
        const val TAG = "CameraVideo"
        const val VIDEO_RETRY_MS = 1_000L
        const val LEGACY_REFRESH_MS = 500L
    }
}
