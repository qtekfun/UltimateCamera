package com.qtekfun.ultimatecamera.core.net.monitor

import android.content.Context
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RtpTransceiver
import org.webrtc.VideoTrack

/**
 * Monitor end of the monitor link (Phase 0 latency spike): connects to the camera at [host], shows its video in a
 * caller-provided [Surface], reads the frame counter of every decoded frame with [stampDecoder] and echoes it straight
 * back to the camera, which computes the latency. A null [stampDecoder] only shows the video.
 *
 * Rendering runs on libwebrtc's own EGL thread (EglRenderer); the frame fills the whole surface, so give the surface
 * the video's aspect ratio. No org.webrtc type is part of this API.
 *
 * Construction throws [IllegalStateException] (or an [UnsatisfiedLinkError]) when libwebrtc cannot be set up; every
 * later failure is reported in [MonitorLinkResult.error] instead.
 */
class MonitorReceiver(
    context: Context,
    val host: String,
    private val stampDecoder: StampDecoder?,
    val network: MonitorNetworkOptions = MonitorNetworkOptions(),
    /** The authenticated control-channel signalling (D70); null dials the legacy socket at [host]. */
    private val signalling: SignallingTransport? = null
) {
    private val echoesSent = AtomicInteger()
    private val session = Session(context)

    val state: StateFlow<MonitorLinkState> get() = session.state

    /**
     * Shows the video in [surface] (for example a SurfaceView's), or stops showing it with null. Call it with null from
     * surfaceDestroyed before the surface goes away: it waits (up to 1 s) until the EGL surface is released.
     */
    fun setOutputSurface(surface: Surface?) = session.setOutputSurface(surface)

    /** Connects to the camera's signalling port and waits for media. True when connected; false with an error otherwise. */
    suspend fun start(): Boolean = session.start()

    /** Frame counts plus a libwebrtc stats snapshot. Works before and after [close]. */
    suspend fun result(): MonitorLinkResult {
        val stats = session.stats()
        val sink = session.stampSink
        return MonitorLinkResult(
            role = MonitorRole.MONITOR,
            peer = session.peer,
            network = network,
            fieldTrials = session.fieldTrials,
            receive = MonitorReceiveCounts(sink.framesReceived, sink.stampsDecoded, echoesSent.get(), sink.readFailures),
            inbound = StatsParser.inboundVideo(stats),
            candidatePair = StatsParser.selectedPair(stats),
            notes = session.notes.toList(),
            error = session.error
        )
    }

    /** Disconnects and releases everything; idempotent. Detach the output surface first if it is going away. */
    suspend fun close() = session.close()

    private inner class Session(context: Context) : PeerSession(context, MonitorRole.MONITOR, network) {
        private val renderer = EglRenderer("monitor-render").apply {
            init(eglBase.eglBaseContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        }

        // Echoes go out on our thread: DataChannel.send blocks until libwebrtc's network thread takes the message,
        // and the decoder thread must not wait for that.
        val stampSink = StampSink(stampDecoder) { counter ->
            scope.launch { if (sendLink(LinkMessage.Echo(counter))) echoesSent.incrementAndGet() }
        }

        @Volatile private var track: VideoTrack? = null

        @Volatile private var released = false
        private val surfaceLock = Any()

        fun setOutputSurface(surface: Surface?) {
            synchronized(surfaceLock) {
                if (released) return
                val done = CountDownLatch(1)
                renderer.releaseEglSurface { done.countDown() }
                done.await(SURFACE_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (surface != null) renderer.createEglSurface(surface)
            }
        }

        override suspend fun openSignalling(): SignallingTransport = signalling ?: SignallingChannel.connect(host, network.port)

        override fun onTrack(transceiver: RtpTransceiver) {
            val video = transceiver.receiver.track() as? VideoTrack ?: return
            track = video
            // Renderer first: it only queues the frame, so the stamp read never delays the picture.
            video.addSink(renderer)
            video.addSink(stampSink)
        }

        override fun onLinkMessage(message: LinkMessage, receivedNanos: Long) {
            if (message is LinkMessage.Ping) sendLink(LinkMessage.Pong(message.sentNanos))
        }

        override fun releaseBeforePeerConnection() {
            track?.let {
                it.removeSink(stampSink)
                it.removeSink(renderer)
            }
            track = null
            synchronized(surfaceLock) {
                released = true
                renderer.release()
            }
        }
    }

    private companion object {
        const val SURFACE_RELEASE_TIMEOUT_MS = 1_000L
    }
}
