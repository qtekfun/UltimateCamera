package com.qtekfun.ultimatecamera.core.net.monitor

import android.content.Context
import android.view.Surface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Camera end of the monitor link (Phase 0 latency spike): sends the proxy video our GPU pipeline renders into
 * [inputSurface] to one monitor phone over WebRTC on the LAN, and measures the latency from the monitor's echoes.
 *
 * Zero copy: [inputSurface] feeds a SurfaceTexture owned by libwebrtc's SurfaceTextureHelper; each frame becomes an
 * OES texture VideoFrame that the hardware encoder draws into MediaCodec's input surface on a shared EGL context.
 *
 * Use: construct (it may block for a few ms setting up EGL; prefer a background thread), start rendering into
 * [inputSurface], call [start] (suspends until the monitor has connected and media flows, or fails), call
 * [frameSubmitted] for every stamped frame right after its eglSwapBuffers, read [result], then stop rendering, release
 * the EGL window surface made for [inputSurface] and [close].
 *
 * Construction throws [IllegalStateException] (or an [UnsatisfiedLinkError]) when libwebrtc cannot be set up; every
 * later failure is reported in [MonitorLinkResult.error] instead.
 */
class MonitorSender(context: Context, val video: MonitorVideoConfig = MonitorVideoConfig(), val network: MonitorNetworkOptions = MonitorNetworkOptions()) {
    private val tracker = MonitorLatencyTracker()
    private val session = Session(context)

    /**
     * Render the proxy here, at exactly [MonitorVideoConfig.width] x [MonitorVideoConfig.height], from any EGL context.
     * Set eglSwapInterval(0) on the EGL window surface made for it: libwebrtc holds one frame at a time while the
     * encoder draws it, and a blocking swap would stall the caller's render thread (and the main recording).
     */
    val inputSurface: Surface get() = session.surface

    val state: StateFlow<MonitorLinkState> get() = session.state

    /** The monitor's address as the signalling socket saw it (`host:port`), once it connected. */
    val peer: String? get() = session.peer

    /**
     * Listens on [MonitorNetworkOptions.port] until the monitor connects (cancel the caller's coroutine to stop
     * waiting), negotiates and waits for media. True when connected; false with [MonitorLinkResult.error] set otherwise.
     */
    suspend fun start(): Boolean = session.start()

    /**
     * The frame stamped with [counter] was just submitted (eglSwapBuffers on [inputSurface]) at [nanoTime]
     * (System.nanoTime). Call on the render thread: no allocation, no blocking. Ignored until the link is connected.
     */
    fun frameSubmitted(counter: Int, nanoTime: Long) {
        if (session.state.value == MonitorLinkState.CONNECTED) tracker.frameSubmitted(counter, nanoTime)
    }

    /** Drops what was measured so far (after a warm-up); the RTT estimate stays. */
    fun resetLatency() = tracker.reset()

    /** Latency so far plus a libwebrtc stats snapshot. Works before and after [close]. */
    suspend fun result(): MonitorLinkResult {
        val stats = session.stats()
        return MonitorLinkResult(
            role = MonitorRole.CAMERA,
            peer = session.peer,
            video = video,
            network = network,
            fieldTrials = session.fieldTrials,
            latency = tracker.snapshot(),
            outbound = StatsParser.outboundVideo(stats),
            candidatePair = StatsParser.selectedPair(stats),
            notes = session.notes.toList(),
            error = session.error
        )
    }

    /** Stops sending and releases everything. Idempotent; [inputSurface] is invalid afterwards. */
    suspend fun close() = session.close()

    private inner class Session(context: Context) : PeerSession(context, MonitorRole.CAMERA, network) {
        private val helper: SurfaceTextureHelper =
            checkNotNull(SurfaceTextureHelper.create("monitor-capture", eglBase.eglBaseContext, true)) { "SurfaceTextureHelper.create failed" }
        private val source: VideoSource = factory.createVideoSource(false, true)
        private val track: VideoTrack = factory.createVideoTrack("proxy", source)
        val surface: Surface
        private val transceiver: RtpTransceiver

        init {
            helper.setTextureSize(video.width, video.height)
            surface = Surface(helper.surfaceTexture)
            // Caps the rate the encoder sees; frames above video.fps are dropped here (and count as not echoed).
            source.adaptOutputFormat(video.width, video.height, video.fps)
            val observer = source.capturerObserver
            observer.onCapturerStarted(true)
            helper.startListening { frame -> observer.onFrameCaptured(frame) }

            val encoding = RtpParameters.Encoding(null, true, null).apply {
                maxBitrateBps = video.bitrateBps
                maxFramerate = video.fps
            }
            transceiver = peerConnection.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("proxy"), listOf(encoding))
            )
            preferCodec()
            keepResolution()
            attachDataChannel(peerConnection.createDataChannel("control", DataChannel.Init().apply { ordered = true }))
        }

        /** Puts the requested codec first; libwebrtc keeps the others as fallbacks in the offer. */
        private fun preferCodec() {
            val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val (preferred, others) = codecs.partition { it.name.equals(video.codec.rtpName, ignoreCase = true) }
            if (preferred.isEmpty()) {
                notes += "no ${video.codec.rtpName} encoder on this phone; offering ${codecs.map { it.name }.distinct()}"
                return
            }
            val result = transceiver.setCodecPreferences(preferred + others)
            if (result.isError) notes += "setCodecPreferences: ${result.error()?.message}"
        }

        /** Under pressure, drop frame rate rather than resolution: the stamp strip and focus checks need full size. */
        private fun keepResolution() {
            val sender = transceiver.sender
            val parameters = sender.parameters
            parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            if (!sender.setParameters(parameters)) notes += "setParameters(degradationPreference) refused"
        }

        override suspend fun openSignalling(): SignallingChannel = SignallingChannel.accept(network.port)

        override suspend fun onPeerHello() {
            createAndSendOffer()
            // Start the bandwidth estimate at the target instead of libwebrtc's ~300 kbps ramp-up.
            if (!peerConnection.setBitrate(null, video.bitrateBps, video.bitrateBps)) notes += "setBitrate refused"
        }

        override fun onConnected() {
            scope.launch {
                while (isActive) {
                    if (sendLink(LinkMessage.Ping(System.nanoTime()))) tracker.pingSent()
                    delay(PING_INTERVAL_MS)
                }
            }
        }

        override fun onLinkMessage(message: LinkMessage, receivedNanos: Long) {
            when (message) {
                is LinkMessage.Echo -> tracker.onEcho(message.counter, receivedNanos)
                is LinkMessage.Pong -> tracker.onPong(message.sentNanos, receivedNanos)
                is LinkMessage.Ping -> sendLink(LinkMessage.Pong(message.sentNanos))
            }
        }

        override fun releaseBeforePeerConnection() {
            helper.stopListening()
            source.capturerObserver.onCapturerStopped()
        }

        override fun releaseAfterPeerConnection() {
            // addTransceiver(track) does not hand the track over: the peer connection does not dispose it.
            track.dispose()
            source.dispose()
            helper.dispose()
            surface.release()
        }
    }

    private companion object {
        const val PING_INTERVAL_MS = 250L
    }
}
