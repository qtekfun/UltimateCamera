package com.qtekfun.ultimatecamera.core.net.pairing

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Unpadded base64url, the text form of every id, key and nonce in the multicamera protocol. */
object B64 {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Decodes [text]; null when it is not unpadded base64url. */
    fun decodeOrNull(text: String): ByteArray? {
        if (text.isEmpty() || !text.all(::isAlphabet)) return null
        return runCatching { decoder.decode(text) }.getOrNull()
    }

    private fun isAlphabet(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
}

/** Random identifiers and keys. One [SecureRandom] per process; it is thread-safe. */
object Secrets {
    /** Bytes of a device id: 96 random bits, 16 base64url characters. */
    const val DEVICE_ID_BYTES = 12

    /** Bytes of a pairing token and of a long-term pair key (256 bits). */
    const val KEY_BYTES = 32

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    /** A new device id, generated once per installation and kept with the paired devices. */
    fun newDeviceId(): String = B64.encode(randomBytes(DEVICE_ID_BYTES))

    fun isValidDeviceId(id: String): Boolean = B64.decodeOrNull(id)?.size == DEVICE_ID_BYTES

    /** Constant-time comparison for MACs and proofs. */
    fun equalsConstantTime(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
