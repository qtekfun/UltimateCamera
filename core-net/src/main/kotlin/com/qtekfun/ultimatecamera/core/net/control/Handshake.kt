package com.qtekfun.ultimatecamera.core.net.control

import com.qtekfun.ultimatecamera.core.net.pairing.B64
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** First connection with the QR token ([PAIR]), or a later one with the stored pair key ([RESUME]). */
@Serializable
enum class HandshakeMode {
    @SerialName("pair")
    PAIR,

    @SerialName("resume")
    RESUME
}

/** Why the server refused a handshake; sent in [HandshakeMessage.Reject]. */
@Serializable
enum class RejectCode {
    /** No protocol version in common. */
    VERSION,

    /** RESUME from a device this one is not paired with (pairing was removed here): pair again. */
    UNKNOWN_DEVICE,

    /** PAIR without a pairing code currently shown, or after it expired or was used. */
    PAIRING_CLOSED,

    /** The proof did not verify: wrong key (or an attacker). */
    BAD_PROOF,

    /** This role may not connect to this device (e.g. camera to camera). */
    ROLE_NOT_ALLOWED,

    /** The handshake was malformed. */
    PROTOCOL
}

/**
 * Plaintext handshake messages, before any key exists. Three messages, mutual authentication with a pre-shared key and
 * an ephemeral ECDH P-256 exchange (similar to Noise `NNpsk0`, built only from java.security / javax.crypto):
 *
 * 1. client → [ClientHello]: versions, identity, mode, 32-byte nonce, ephemeral public key.
 * 2. server → [ServerHello] (or [Reject]): its identity, nonce, ephemeral key and `proof = HMAC(k_sf, transcript)`.
 * 3. client → [ClientFinish] with `HMAC(k_cf, transcript)` once it has verified the server's proof.
 *
 * `transcript` is SHA-256 over every field of both hellos (length-prefixed). Keys: `prk = HKDF-Extract(salt =
 * transcript, ikm = psk ‖ ecdh)`, then HKDF-Expand with distinct labels for both proofs, both traffic directions, the
 * clock packets and (PAIR) the long-term pair key. Knowing the psk is needed to pass the proofs; the ECDH secret is
 * needed to read the traffic, so a photographed QR or a stolen pair key does not decrypt recorded past sessions
 * (forward secrecy against passive listeners). An active man-in-the-middle needs the psk.
 */
@Serializable
sealed interface HandshakeMessage {
    @Serializable
    @SerialName("client_hello")
    data class ClientHello(
        val protocol: Int,
        val minProtocol: Int,
        val deviceId: String,
        val name: String,
        val role: DeviceRole,
        val mode: HandshakeMode,
        /** Device id the client expects to reach (from the QR or the pairing list). */
        val target: String,
        val nonce: String,
        val key: String
    ) : HandshakeMessage

    @Serializable
    @SerialName("server_hello")
    data class ServerHello(
        val protocol: Int,
        val deviceId: String,
        val name: String,
        val role: DeviceRole,
        val nonce: String,
        val key: String,
        val proof: String
    ) : HandshakeMessage

    @Serializable
    @SerialName("client_finish")
    data class ClientFinish(val proof: String) : HandshakeMessage

    @Serializable
    @SerialName("reject")
    data class Reject(val code: RejectCode) : HandshakeMessage
}

/** Raised when a handshake fails; [code] is what was (or would be) sent to the peer. */
class HandshakeException(val code: RejectCode, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Who this phone is, for the handshake. */
data class LocalIdentity(val deviceId: String, val name: String, val role: DeviceRole)

/** The authenticated peer and the session keys. Keys are secret; [toString] hides them. */
class Session(
    val peerId: String,
    val peerName: String,
    val peerRole: DeviceRole,
    val protocol: Int,
    val mode: HandshakeMode,
    internal val sendKey: ByteArray,
    internal val receiveKey: ByteArray,
    /** Authenticates the UDP clock packets of this session (see `ClockPacket`). */
    val clockKey: ByteArray,
    /** After a PAIR handshake: the long-term key both sides store for later RESUME handshakes. */
    val newPairKey: ByteArray?
) {
    override fun toString(): String = "Session($peerId, $peerName, $peerRole, v$protocol, $mode)"
}

internal object HandshakeCodec {
    const val MAX_BYTES = 4 * 1024

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(message: HandshakeMessage): ByteArray = json.encodeToString(HandshakeMessage.serializer(), message).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): HandshakeMessage = try {
        json.decodeFromString(HandshakeMessage.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (e: SerializationException) {
        throw HandshakeException(RejectCode.PROTOCOL, "bad handshake message: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw HandshakeException(RejectCode.PROTOCOL, "bad handshake message: ${e.message}", e)
    }
}

/** Crypto primitives of the handshake. */
internal object HandshakeCrypto {
    const val NONCE_BYTES = 32
    private const val HMAC = "HmacSHA256"
    private const val LABEL = "ultimatecamera-control-v1"
    private const val MAX_EXPAND = 255 * 32

    fun newEphemeral(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun ecdh(own: KeyPair, peerEncoded: ByteArray): ByteArray = try {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerEncoded))
        KeyAgreement.getInstance("ECDH").run {
            init(own.private)
            doPhase(peer, true)
            generateSecret()
        }
    } catch (e: GeneralSecurityException) {
        throw HandshakeException(RejectCode.PROTOCOL, "bad ephemeral key: ${e.message}", e)
    } catch (e: ClassCastException) {
        throw HandshakeException(RejectCode.PROTOCOL, "bad ephemeral key: ${e.message}", e)
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
        init(SecretKeySpec(key, HMAC))
        doFinal(data)
    }

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    fun hkdfExpand(prk: ByteArray, info: String, length: Int): ByteArray = hkdfExpand(prk, info.toByteArray(Charsets.UTF_8), length)

    /** HKDF-Expand (RFC 5869) for up to 255 blocks. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..MAX_EXPAND)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun transcript(hello: HandshakeMessage.ClientHello, server: ServerPart, protocol: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun field(text: String) = text.toByteArray(Charsets.UTF_8).let {
                out.writeInt(it.size)
                out.write(it)
            }
            field(LABEL)
            out.writeInt(protocol)
            field(hello.mode.name)
            field(hello.deviceId)
            field(hello.name)
            field(hello.role.wire)
            field(hello.target)
            field(hello.nonce)
            field(hello.key)
            field(server.deviceId)
            field(server.name)
            field(server.role.wire)
            field(server.nonce)
            field(server.key)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }

    data class ServerPart(val deviceId: String, val name: String, val role: DeviceRole, val nonce: String, val key: String)

    class Keys(prk: ByteArray, val transcript: ByteArray) {
        val serverFinish = hkdfExpand(prk, "server finish", Secrets.KEY_BYTES)
        val clientFinish = hkdfExpand(prk, "client finish", Secrets.KEY_BYTES)
        val clientToServer = hkdfExpand(prk, "c2s traffic", Secrets.KEY_BYTES)
        val serverToClient = hkdfExpand(prk, "s2c traffic", Secrets.KEY_BYTES)
        val clock = hkdfExpand(prk, "clock", Secrets.KEY_BYTES)
        val pair = hkdfExpand(prk, "pair key", Secrets.KEY_BYTES)

        fun serverProof() = hmac(serverFinish, transcript)

        fun clientProof() = hmac(clientFinish, transcript)
    }

    fun keys(psk: ByteArray, dh: ByteArray, transcript: ByteArray) = Keys(hkdfExtract(transcript, psk + dh), transcript)

    fun decodeField(text: String, expectedSize: Int? = null): ByteArray {
        val bytes = B64.decodeOrNull(text) ?: throw HandshakeException(RejectCode.PROTOCOL, "bad base64 field")
        if (expectedSize != null && bytes.size != expectedSize) throw HandshakeException(RejectCode.PROTOCOL, "bad field size")
        return bytes
    }
}

/**
 * Client side of the handshake, as a pure step machine: [hello], then [onServerMessage] returns the [ClientFinish] and
 * the [Session]. [psk] is the QR token (PAIR) or the stored pair key (RESUME) of device [target].
 */
class ClientHandshake(private val local: LocalIdentity, private val mode: HandshakeMode, private val target: String, private val psk: ByteArray) {
    private val ephemeral = HandshakeCrypto.newEphemeral()
    private val nonce = B64.encode(Secrets.randomBytes(HandshakeCrypto.NONCE_BYTES))
    private var sent: HandshakeMessage.ClientHello? = null

    fun hello(): HandshakeMessage.ClientHello = HandshakeMessage.ClientHello(
        protocol = ControlProtocol.VERSION,
        minProtocol = ControlProtocol.MIN_VERSION,
        deviceId = local.deviceId,
        name = local.name,
        role = local.role,
        mode = mode,
        target = target,
        nonce = nonce,
        key = B64.encode(ephemeral.public.encoded)
    ).also { sent = it }

    @Suppress("ThrowsCount")
    fun onServerMessage(message: HandshakeMessage): Pair<HandshakeMessage.ClientFinish, Session> {
        val hello = checkNotNull(sent) { "hello() first" }
        val server = when (message) {
            is HandshakeMessage.ServerHello -> message
            is HandshakeMessage.Reject -> throw HandshakeException(message.code, "server rejected: ${message.code}")
            else -> throw HandshakeException(RejectCode.PROTOCOL, "unexpected ${message::class.simpleName}")
        }
        if (server.deviceId != target) throw HandshakeException(RejectCode.BAD_PROOF, "reached ${server.deviceId}, expected $target")
        if (server.protocol !in
            ControlProtocol.MIN_VERSION..ControlProtocol.VERSION
        ) {
            throw HandshakeException(RejectCode.VERSION, "server chose v${server.protocol}")
        }
        HandshakeCrypto.decodeField(server.nonce, HandshakeCrypto.NONCE_BYTES)
        val dh = HandshakeCrypto.ecdh(ephemeral, HandshakeCrypto.decodeField(server.key))
        val part = HandshakeCrypto.ServerPart(server.deviceId, server.name, server.role, server.nonce, server.key)
        val keys = HandshakeCrypto.keys(psk, dh, HandshakeCrypto.transcript(hello, part, server.protocol))
        if (!Secrets.equalsConstantTime(keys.serverProof(), HandshakeCrypto.decodeField(server.proof))) {
            throw HandshakeException(RejectCode.BAD_PROOF, "server proof does not verify")
        }
        val session = Session(
            peerId = server.deviceId,
            peerName = server.name,
            peerRole = server.role,
            protocol = server.protocol,
            mode = mode,
            sendKey = keys.clientToServer,
            receiveKey = keys.serverToClient,
            clockKey = keys.clock,
            newPairKey = if (mode == HandshakeMode.PAIR) keys.pair else null
        )
        return HandshakeMessage.ClientFinish(B64.encode(keys.clientProof())) to session
    }
}

/** What the server knows: pair keys by device id and the currently shown pairing token, if any. */
interface Keyring {
    fun pairKey(deviceId: String): ByteArray?

    /** Token of the QR code on screen now, or null when none is shown or it expired. */
    fun pairingToken(): ByteArray?

    /** Whether a peer with [role] may open a session with this device. */
    fun accepts(role: DeviceRole): Boolean = true
}

/** Server side: [onClientHello] returns the reply ([ServerHello] or [Reject]); [onClientFinish] the [Session]. */
class ServerHandshake(private val local: LocalIdentity, private val keyring: Keyring) {
    private var pending: Pending? = null

    private class Pending(val hello: HandshakeMessage.ClientHello, val keys: HandshakeCrypto.Keys, val protocol: Int)

    /** The reply to send. A [HandshakeMessage.Reject] means the handshake is over: send it, then close. */
    @Suppress("ReturnCount")
    fun onClientHello(message: HandshakeMessage): HandshakeMessage {
        val hello = message as? HandshakeMessage.ClientHello ?: return HandshakeMessage.Reject(RejectCode.PROTOCOL)
        val protocol = minOf(hello.protocol, ControlProtocol.VERSION)
        if (protocol < maxOf(hello.minProtocol, ControlProtocol.MIN_VERSION)) return HandshakeMessage.Reject(RejectCode.VERSION)
        if (!Secrets.isValidDeviceId(hello.deviceId) || hello.target != local.deviceId) return HandshakeMessage.Reject(RejectCode.PROTOCOL)
        if (!keyring.accepts(hello.role)) return HandshakeMessage.Reject(RejectCode.ROLE_NOT_ALLOWED)
        val psk = when (hello.mode) {
            HandshakeMode.PAIR -> keyring.pairingToken() ?: return HandshakeMessage.Reject(RejectCode.PAIRING_CLOSED)
            HandshakeMode.RESUME -> keyring.pairKey(hello.deviceId) ?: return HandshakeMessage.Reject(RejectCode.UNKNOWN_DEVICE)
        }
        val ephemeral = HandshakeCrypto.newEphemeral()
        val dh = try {
            HandshakeCrypto.decodeField(hello.nonce, HandshakeCrypto.NONCE_BYTES)
            HandshakeCrypto.ecdh(ephemeral, HandshakeCrypto.decodeField(hello.key))
        } catch (e: HandshakeException) {
            return HandshakeMessage.Reject(e.code)
        }
        val part = HandshakeCrypto.ServerPart(
            local.deviceId,
            local.name,
            local.role,
            B64.encode(Secrets.randomBytes(HandshakeCrypto.NONCE_BYTES)),
            B64.encode(ephemeral.public.encoded)
        )
        val keys = HandshakeCrypto.keys(psk, dh, HandshakeCrypto.transcript(hello, part, protocol))
        pending = Pending(hello, keys, protocol)
        return HandshakeMessage.ServerHello(protocol, part.deviceId, part.name, part.role, part.nonce, part.key, B64.encode(keys.serverProof()))
    }

    /** Verifies the client's proof. Throws [HandshakeException] (send a [HandshakeMessage.Reject] and close). */
    fun onClientFinish(message: HandshakeMessage): Session {
        val state = pending ?: throw HandshakeException(RejectCode.PROTOCOL, "finish before hello")
        val finish = message as? HandshakeMessage.ClientFinish ?: throw HandshakeException(RejectCode.PROTOCOL, "expected client_finish")
        if (!Secrets.equalsConstantTime(state.keys.clientProof(), HandshakeCrypto.decodeField(finish.proof))) {
            throw HandshakeException(RejectCode.BAD_PROOF, "client proof does not verify")
        }
        pending = null
        return Session(
            peerId = state.hello.deviceId,
            peerName = state.hello.name,
            peerRole = state.hello.role,
            protocol = state.protocol,
            mode = state.hello.mode,
            sendKey = state.keys.serverToClient,
            receiveKey = state.keys.clientToServer,
            clockKey = state.keys.clock,
            newPairKey = if (state.hello.mode == HandshakeMode.PAIR) state.keys.pair else null
        )
    }
}
