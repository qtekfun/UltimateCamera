package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The short numeric code shown next to the pairing QR (D55), for a monitor that cannot scan. The pairing token (the
 * pre-shared key of the PAIR handshake) is derived from it, salted with the showing phone's device id and stretched
 * with PBKDF2, so the QR and the code are the same secret. The code is single use (see [PairingWindow]) and the
 * handshake times out after 5 s, so the stretched 8 digits cannot be brute-forced from one captured exchange in time.
 */
object ShortCode {
    const val DIGITS = 8
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = Secrets.KEY_BYTES * 8
    private const val SALT_LABEL = "ultimatecamera-pair-code-v1:"
    private val random = SecureRandom()

    /** A new uniformly random code of [DIGITS] digits (leading zeros kept). */
    fun generate(): String = buildString { repeat(DIGITS) { append(random.nextInt(10)) } }

    /** Digits only, without spaces or dashes; null unless exactly [DIGITS] digits remain. */
    fun normalize(input: String): String? = input.filterNot { it == ' ' || it == '-' }.takeIf { it.length == DIGITS && it.all(Char::isDigit) }

    /** `1234 5678`: two groups are easier to read out and type. */
    fun format(code: String): String = if (code.length == DIGITS) "${code.substring(0, DIGITS / 2)} ${code.substring(DIGITS / 2)}" else code

    /** The 32-byte pairing token for [code] shown by device [deviceId]. */
    fun token(deviceId: String, code: String): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), (SALT_LABEL + deviceId).toByteArray(Charsets.UTF_8), ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
