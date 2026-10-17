package com.qtekfun.ultimatecamera.core.net.monitor

import android.content.Context
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * What both ends of the monitor link share: the libwebrtc factory and peer connection, TCP signalling, the "control"
 * data channel, stats, and an ordered teardown.
 *
 * Threading: our own state changes on one private thread ([dispatcher]); libwebrtc callbacks arrive on its signalling
 * thread and hop to it, except data-channel messages, which are handled in place so echoes are timed without a thread
 * hop. Nothing here runs on the caller's main or render thread.
 */
internal abstract class PeerSession(context: Context, val role: MonitorRole, val network: MonitorNetworkOptions) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "monitor-${role.name.lowercase()}") }
    protected val dispatcher = executor.asCoroutineDispatcher()
    protected val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val fieldTrials: String = fieldTrialsFor(role, network)

    /** Shared EGL context: the encoder draws our OES frames into MediaCodec's input surface, the decoder outputs textures. */
    protected val eglBase: EglBase = EglBase.create()

    // Required by the factory; we add no audio track, so it never opens an AudioRecord or AudioTrack.
    private val audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        .createAudioDeviceModule()
    protected val factory: PeerConnectionFactory
    protected val peerConnection: PeerConnection

    private val _state = MutableStateFlow(MonitorLinkState.IDLE)
    val state: StateFlow<MonitorLinkState> = _state

    @Volatile var error: String? = null
        private set

    @Volatile var peer: String? = null
        private set

    /** Non-fatal findings for the result (a codec preference libwebrtc refused, a lost connection). */
    val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile private var channel: SignallingTransport? = null
    private val connected = CompletableDeferred<Boolean>()
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private val closing = AtomicBoolean(false)

    /** Set once teardown has begun on [dispatcher]; from then on nothing touches libwebrtc objects. */
    private val closed = AtomicBoolean(false)

    @Volatile private var peerEnded = false

    @Volatile private var dataChannel: DataChannel? = null

    @Volatile private var finalStats: List<StatsEntry>? = null

    init {
        initializeOnce(context)
        val options = PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = network.disableNetworkMonitor
            // LAN only: never gather on mobile data or a VPN. Loopback is ignored by default.
            networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or PeerConnectionFactory.Options.ADAPTER_TYPE_VPN
        }
        // DefaultVideo*Factory = libwebrtc's MediaCodec (hardware) factory first, software (VP8/VP9/AV1) only as a
        // fallback. There is no software H.264/H.265 in this build, so an H.264 link is always hardware; the stats'
        // encoderImplementation/decoderImplementation show which codec was used.
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setFieldTrials(fieldTrials)
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, false, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        peerConnection = checkNotNull(factory.createPeerConnection(rtcConfiguration(network), Observer())) { "createPeerConnection failed" }
    }

    /** Opens signalling: the control channel (D70), or the legacy socket (accept on the camera, connect on the monitor). */
    protected abstract suspend fun openSignalling(): SignallingTransport

    /** Camera: the monitor said hello, so create and send the offer. Runs on [dispatcher]. */
    protected open suspend fun onPeerHello() {}

    /** A measurement message on the data channel, handled on the libwebrtc thread that delivered it. */
    protected abstract fun onLinkMessage(message: LinkMessage, receivedNanos: Long)

    /** A remote track arrived (monitor). On libwebrtc's signalling thread. */
    protected open fun onTrack(transceiver: RtpTransceiver) {}

    /** Media and data channel are up. On a libwebrtc thread: only start coroutines or set flags here. */
    protected open fun onConnected() {}

    /** Releases role resources that must go before the peer connection (sinks, capture). Runs on [dispatcher]. */
    protected open fun releaseBeforePeerConnection() {}

    /** Releases role resources that must outlive the peer connection (tracks, sources, surfaces). Runs on [dispatcher]. */
    protected open fun releaseAfterPeerConnection() {}

    /**
     * Signals, negotiates and waits until media and the data channel are up. Returns false on failure or when [close]
     * interrupts it, with [error] set. The camera waits for the monitor until cancelled; after that, connecting has
     * [CONNECT_TIMEOUT_MS].
     */
    suspend fun start(): Boolean {
        if (closed.get()) return false
        check(_state.compareAndSet(MonitorLinkState.IDLE, MonitorLinkState.SIGNALLING)) { "start() called twice" }
        val job = scope.async { connect() }
        return try {
            job.await()
        } catch (e: CancellationException) {
            if (closed.get()) return false
            job.cancel()
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught") // Link boundary: a network or libwebrtc failure is a result, not a crash.
    private suspend fun connect(): Boolean = try {
        val ch = openSignalling()
        channel = ch
        peer = ch.remote
        if (closed.get()) ch.close()
        send(SignallingMessage.Hello(SignallingCodec.PROTOCOL_VERSION, role))
        scope.launch(Dispatchers.IO) { readSignalling(ch) }
        // Only if nothing else happened meanwhile (a fast connect, or a failure).
        _state.compareAndSet(MonitorLinkState.SIGNALLING, MonitorLinkState.CONNECTING)
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
        if (ok == null) fail("timed out after $CONNECT_TIMEOUT_MS ms waiting for media and the data channel")
        ok == true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fail("${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /** Sends a measurement message on the data channel; false when it is not open. Any thread except the render thread. */
    fun sendLink(message: LinkMessage): Boolean {
        val dc = dataChannel ?: return false
        if (closed.get() || dc.state() != DataChannel.State.OPEN) return false
        return dc.send(DataChannel.Buffer(ByteBuffer.wrap(message.encode()), true))
    }

    /** The current libwebrtc stats report; after [close], the last one taken before teardown. Empty if none arrived in time. */
    suspend fun stats(): List<StatsEntry> {
        if (closed.get()) return finalStats.orEmpty()
        return withContext(dispatcher) { collectStats() }
    }

    private suspend fun collectStats(): List<StatsEntry> = withTimeoutOrNull(STATS_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            peerConnection.getStats { report ->
                cont.resume(report.statsMap.values.map { StatsEntry(it.id, it.type, it.members) })
            }
        }
    } ?: emptyList()

    /** Records the first error, marks the link failed and unblocks [start]. Any thread. */
    fun fail(message: String) {
        if (closed.get()) return
        if (error == null) error = message
        _state.value = MonitorLinkState.FAILED
        connected.complete(false)
    }

    /** Idempotent ordered teardown; call from any thread except libwebrtc's own (never from one of its callbacks). */
    @Suppress("TooGenericExceptionCaught") // Teardown boundary: one failed release must not leak the rest.
    suspend fun close() {
        if (!closing.compareAndSet(false, true)) return
        withContext(NonCancellable + dispatcher) {
            if (_state.value == MonitorLinkState.CONNECTED) finalStats = collectStats()
            closed.set(true)
            scope.coroutineContext.cancelChildren()
            channel?.let { ch ->
                runCatching { withContext(Dispatchers.IO) { ch.send(SignallingMessage.Bye()) } }
                ch.close()
            }
            val steps = listOf(
                ::releaseBeforePeerConnection,
                ::releaseDataChannel,
                peerConnection::dispose,
                ::releaseAfterPeerConnection,
                factory::dispose,
                audioModule::release,
                eglBase::release
            )
            for (step in steps) {
                try {
                    step()
                } catch (e: Exception) {
                    notes += "close: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
            _state.value = if (error == null) MonitorLinkState.CLOSED else MonitorLinkState.FAILED
        }
        scope.cancel()
        dispatcher.close()
    }

    private fun releaseDataChannel() {
        dataChannel?.let {
            it.unregisterObserver()
            it.close()
            it.dispose()
        }
        dataChannel = null
    }

    protected fun attachDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) markConnected()
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val now = System.nanoTime()
                if (buffer.binary) LinkMessage.decode(buffer.data)?.let { onLinkMessage(it, now) }
            }
        })
        if (dc.state() == DataChannel.State.OPEN) markConnected()
    }

    protected suspend fun createAndSendOffer() {
        val offer = createSdp(offer = true)
        setSdp(local = true, offer)
        send(SignallingMessage.Offer(offer.description))
    }

    private fun markConnected() {
        if (_state.compareAndSet(MonitorLinkState.CONNECTING, MonitorLinkState.CONNECTED) ||
            _state.compareAndSet(MonitorLinkState.SIGNALLING, MonitorLinkState.CONNECTED)
        ) {
            onConnected()
            connected.complete(true)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Reader boundary: a broken socket or bad message ends the link with an error.
    private suspend fun readSignalling(ch: SignallingTransport) {
        try {
            while (true) {
                val message = ch.receive() ?: break
                withContext(dispatcher) { handle(message) }
            }
            if (_state.value != MonitorLinkState.CONNECTED) fail("signalling closed by the peer")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // After close() the socket throws; that is not an error of the link. Once connected, signalling is idle.
            if (!closed.get() && _state.value != MonitorLinkState.CONNECTED) fail("signalling: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun handle(message: SignallingMessage) {
        when (message) {
            is SignallingMessage.Hello -> when {
                message.protocol != SignallingCodec.PROTOCOL_VERSION ->
                    fail("peer speaks signalling protocol ${message.protocol}, we speak ${SignallingCodec.PROTOCOL_VERSION}")

                message.role == role -> fail("both phones are ${role.name}")

                else -> onPeerHello()
            }

            is SignallingMessage.Offer -> {
                setSdp(local = false, SessionDescription(SessionDescription.Type.OFFER, message.sdp))
                flushCandidates()
                val answer = createSdp(offer = false)
                setSdp(local = true, answer)
                send(SignallingMessage.Answer(answer.description))
            }

            is SignallingMessage.Answer -> {
                setSdp(local = false, SessionDescription(SessionDescription.Type.ANSWER, message.sdp))
                flushCandidates()
            }

            is SignallingMessage.Ice -> {
                val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex, message.candidate)
                if (remoteDescriptionSet) peerConnection.addIceCandidate(candidate) else pendingCandidates += candidate
            }

            is SignallingMessage.Bye -> {
                peerEnded = true
                if (_state.value == MonitorLinkState.CONNECTED) notes += "peer ended the session" else fail("peer ended the session: ${message.reason}")
            }
        }
    }

    private fun flushCandidates() {
        remoteDescriptionSet = true
        pendingCandidates.forEach { peerConnection.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    /** Sends on the signalling socket; called on [dispatcher], so messages keep their order. */
    private suspend fun send(message: SignallingMessage) {
        val ch = channel ?: return
        withContext(Dispatchers.IO) { ch.send(message) }
    }

    private suspend fun createSdp(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)

            override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException("create ${if (offer) "offer" else "answer"}: $error"))

            override fun onSetSuccess() = Unit

            override fun onSetFailure(error: String?) = Unit
        }
        if (offer) peerConnection.createOffer(observer, MediaConstraints()) else peerConnection.createAnswer(observer, MediaConstraints())
    }

    private suspend fun setSdp(local: Boolean, sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(created: SessionDescription?) = Unit

            override fun onCreateFailure(error: String?) = Unit

            override fun onSetSuccess() = cont.resume(Unit)

            override fun onSetFailure(error: String?) =
                cont.resumeWithException(IllegalStateException("set ${if (local) "local" else "remote"} ${sdp.type.canonicalForm()}: $error"))
        }
        if (local) peerConnection.setLocalDescription(observer, sdp) else peerConnection.setRemoteDescription(observer, sdp)
    }

    /** libwebrtc callbacks, on its signalling thread: each one hops to [dispatcher] or only touches thread-safe state. */
    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch { send(SignallingMessage.Ice(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.FAILED -> if (peerEnded) notes += "connection closed by the peer" else fail("ICE/DTLS connection failed")
                PeerConnection.PeerConnectionState.DISCONNECTED -> notes += "connection lost at ${System.nanoTime() / NANOS_PER_MS} ms (nanoTime)"
                else -> Unit
            }
        }

        override fun onDataChannel(dc: DataChannel) = attachDataChannel(dc)

        override fun onTrack(transceiver: RtpTransceiver) = this@PeerSession.onTrack(transceiver)

        override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onRenegotiationNeeded() = Unit
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        private const val STATS_TIMEOUT_MS = 2_000L
        private const val NANOS_PER_MS = 1_000_000L
        private val initialized = AtomicBoolean(false)

        /** PeerConnectionFactory.initialize is process-wide: once, without field trials (those are set per factory). */
        private fun initializeOnce(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }

        /** Field trials per role; see [MonitorNetworkOptions.zeroPlayoutDelay]. All unverified on Android m149. */
        fun fieldTrialsFor(role: MonitorRole, network: MonitorNetworkOptions): String = when {
            !network.zeroPlayoutDelay -> ""
            role == MonitorRole.CAMERA -> "WebRTC-ForceSendPlayoutDelay/min_ms:0,max_ms:0/"
            else -> "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:0/WebRTC-ZeroPlayoutDelay/min_pacing:0ms,max_decode_queue_size:1/"
        }

        /**
         * LAN only, lowest latency: no STUN/TURN (host candidates only), UDP only, one bundled transport, no CPU-overuse
         * adaptation (it would scale our proxy down behind our back), continual gathering so a Wi-Fi change can recover.
         */
        private fun rtcConfiguration(network: MonitorNetworkOptions) = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            enableCpuOveruseDetection = false
            enableDscp = network.enableDscp
        }
    }
}
