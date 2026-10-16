package com.qtekfun.ultimatecamera.core.net.pairing

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.Serializable

/** One address a peer may be reached at: an IPv4/IPv6 literal or an mDNS host name, and the control port. */
@Serializable
data class Endpoint(val host: String, val port: Int) {
    init {
        require(port in 1..MAX_PORT) { "port out of range: $port" }
        require(PairingPayload.isValidHost(host)) { "bad host: $host" }
    }

    /** `host:port`, with brackets around an IPv6 literal. */
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        const val MAX_PORT = 65_535

        /** Parses `host:port` or `[v6]:port`; null when malformed. */
        fun parseOrNull(text: String): Endpoint? {
            val (host, portText) = if (text.startsWith("[")) {
                val close = text.indexOf("]:")
                if (close < 0) return null
                text.substring(1, close) to text.substring(close + 2)
            } else {
                val colon = text.lastIndexOf(':')
                if (colon <= 0 || text.indexOf(':') != colon) return null
                text.substring(0, colon) to text.substring(colon + 1)
            }
            val port = portText.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
            return if (PairingPayload.isValidHost(host)) Endpoint(host, port) else null
        }
    }
}

/** Why a scanned QR code was refused. Each one has a user-visible string (ControlStrings). */
enum class PairingError {
    /** Not an UltimateCamera pairing code at all. */
    NOT_A_PAIRING_CODE,

    /** Made by a newer app version: update this phone. */
    UNSUPPORTED_VERSION,

    /** A field is missing or malformed. */
    MALFORMED,

    /** The code is older than its expiry time: show a new one. */
    EXPIRED,

    /** The code belongs to this same phone. */
    SELF
}

sealed interface PairingParseResult {
    data class Ok(val payload: PairingPayload) : PairingParseResult

    data class Invalid(val error: PairingError, val detail: String) : PairingParseResult
}

/**
 * What a pairing QR code carries (REQ-NET-002). The phone that shows it (usually the camera or the master) accepts a
 * connection from the phone that scans it during [expiresAtEpochSeconds]; the [token] authenticates that first
 * connection and is used once (see `Handshake`): the long-term pair key is derived from it and an ephemeral ECDH
 * exchange, so a photo of the QR alone is not enough to listen to later sessions.
 *
 * Text form, versioned and short enough for a version 7–8 QR code (about 200 characters):
 * `ultimatecamera://pair?v=1&id=<device id>&n=<name>&r=<role>&h=<host:port>,<host:port>&t=<token>&x=<expiry>`
 * - `v` protocol version of the code; a newer one is refused with [PairingError.UNSUPPORTED_VERSION].
 * - `id` 16 base64url characters (96 random bits), `n` the device name (percent-encoded, at most [MAX_NAME_CHARS]).
 * - `r` `master`, `camera` or `monitor`; `h` 1..[MAX_ENDPOINTS] candidate endpoints of the control port.
 * - `t` 32-byte random token, base64url; `x` expiry, Unix seconds. Unknown parameters are ignored (forward compatible).
 */
data class PairingPayload(
    val deviceId: String,
    val name: String,
    val role: DeviceRole,
    val endpoints: List<Endpoint>,
    val token: ByteArray,
    val expiresAtEpochSeconds: Long,
    val version: Int = VERSION
) {
    init {
        require(Secrets.isValidDeviceId(deviceId)) { "bad device id" }
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS) { "bad name" }
        require(endpoints.size in 1..MAX_ENDPOINTS) { "bad endpoint count" }
        require(token.size == Secrets.KEY_BYTES) { "bad token" }
    }

    fun encode(): String = buildString {
        append(PREFIX)
        append("v=").append(version)
        append("&id=").append(deviceId)
        append("&n=").append(URLEncoder.encode(name, Charsets.UTF_8.name()))
        append("&r=").append(role.wire)
        append("&h=").append(endpoints.joinToString(",") { URLEncoder.encode(it.toString(), Charsets.UTF_8.name()) })
        append("&t=").append(B64.encode(token))
        append("&x=").append(expiresAtEpochSeconds)
    }

    override fun equals(other: Any?): Boolean = other is PairingPayload &&
        deviceId == other.deviceId &&
        name == other.name &&
        role == other.role &&
        endpoints == other.endpoints &&
        token.contentEquals(other.token) &&
        expiresAtEpochSeconds == other.expiresAtEpochSeconds &&
        version == other.version

    override fun hashCode(): Int = deviceId.hashCode() * 31 + token.contentHashCode()

    override fun toString(): String = "PairingPayload(v$version, $deviceId, $name, $role, $endpoints, token=<hidden>, x=$expiresAtEpochSeconds)"

    companion object {
        const val VERSION = 1
        const val PREFIX = "ultimatecamera://pair?"
        const val MAX_NAME_CHARS = 40
        const val MAX_ENDPOINTS = 4

        /** How long a freshly shown code stays valid. */
        const val DEFAULT_LIFETIME_SECONDS = 300L

        /** A new code for this phone, valid for [lifetimeSeconds] from [nowEpochMs]. */
        fun create(
            deviceId: String,
            name: String,
            role: DeviceRole,
            endpoints: List<Endpoint>,
            nowEpochMs: Long,
            lifetimeSeconds: Long = DEFAULT_LIFETIME_SECONDS
        ): PairingPayload = PairingPayload(
            deviceId = deviceId,
            name = name.trim().take(MAX_NAME_CHARS),
            role = role,
            endpoints = endpoints.distinct().take(MAX_ENDPOINTS),
            token = Secrets.randomBytes(Secrets.KEY_BYTES),
            expiresAtEpochSeconds = nowEpochMs / MS_PER_S + lifetimeSeconds
        )

        /**
         * Parses and validates a scanned code. [ownDeviceId] refuses this phone's own code; [nowEpochMs] checks the expiry
         * (null skips it, e.g. for tests of the format).
         */
        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        fun parse(text: String, nowEpochMs: Long?, ownDeviceId: String? = null): PairingParseResult {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX) || trimmed.length > MAX_TEXT_CHARS) {
                return PairingParseResult.Invalid(PairingError.NOT_A_PAIRING_CODE, "prefix")
            }
            val params = HashMap<String, String>()
            for (pair in trimmed.substring(PREFIX.length).split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) return malformed("parameter '$pair'")
                val key = pair.substring(0, eq)
                if (key in params) return malformed("repeated '$key'")
                params[key] = pair.substring(eq + 1)
            }
            val version = params["v"]?.toIntOrNull() ?: return malformed("v")
            if (version > VERSION) return PairingParseResult.Invalid(PairingError.UNSUPPORTED_VERSION, "v=$version")
            if (version < 1) return malformed("v=$version")
            val id = params["id"]?.takeIf { Secrets.isValidDeviceId(it) } ?: return malformed("id")
            val name = params["n"]?.let { decode(it) }?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_CHARS }
                ?: return malformed("n")
            val role = params["r"]?.let { DeviceRole.fromWire(it) } ?: return malformed("r")
            val hostItems = params["h"]?.split(',')?.takeIf { it.size in 1..MAX_ENDPOINTS } ?: return malformed("h")
            val endpoints = hostItems.map { item -> decode(item)?.let { Endpoint.parseOrNull(it) } ?: return malformed("h '$item'") }
            val token = params["t"]?.let { B64.decodeOrNull(it) }?.takeIf { it.size == Secrets.KEY_BYTES } ?: return malformed("t")
            val expiry = params["x"]?.toLongOrNull() ?: return malformed("x")
            if (nowEpochMs != null && nowEpochMs / MS_PER_S > expiry) {
                return PairingParseResult.Invalid(PairingError.EXPIRED, "expired at $expiry")
            }
            if (ownDeviceId != null && id == ownDeviceId) return PairingParseResult.Invalid(PairingError.SELF, id)
            return PairingParseResult.Ok(PairingPayload(id, name, role, endpoints.distinct(), token, expiry, version))
        }

        /** IPv4 literal, IPv6 literal (no brackets, no zone) or a DNS/mDNS host name. No lookups. */
        fun isValidHost(host: String): Boolean = when {
            host.isEmpty() || host.length > MAX_HOST_CHARS -> false
            ':' in host -> isIpv6Literal(host)
            host.all { it.isDigit() || it == '.' } -> isIpv4Literal(host)
            else -> host.split('.').all(::isDnsLabel)
        }

        private fun isIpv6Literal(host: String): Boolean =
            host.count { it == ':' } >= 2 && host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

        private fun isIpv4Literal(host: String): Boolean {
            val parts = host.split('.')
            return parts.size == IPV4_PARTS && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.toInt() <= MAX_OCTET }
        }

        private fun isDnsLabel(label: String): Boolean = label.isNotEmpty() &&
            label.length <= MAX_LABEL_CHARS &&
            label.first() != '-' &&
            label.last() != '-' &&
            label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

        private fun decode(text: String): String? = runCatching { URLDecoder.decode(text, Charsets.UTF_8.name()) }.getOrNull()

        private fun malformed(detail: String) = PairingParseResult.Invalid(PairingError.MALFORMED, detail)

        private const val MS_PER_S = 1_000L
        private const val MAX_TEXT_CHARS = 1_024
        private const val MAX_HOST_CHARS = 253
        private const val MAX_LABEL_CHARS = 63
        private const val IPV4_PARTS = 4
        private const val MAX_OCTET = 255
    }
}
