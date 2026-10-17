package com.qtekfun.ultimatecamera.core.backup

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The optional password envelope of a backup (D65): the zip, encrypted in chunks so neither side holds more than one
 * chunk in memory. Only `javax.crypto` (no new dependency).
 *
 * ```
 * header  "UCBK" | version 1 | kdf 1 (PBKDF2-HMAC-SHA256) | iterations u32 | salt 16 | nonce prefix 8 | chunk size u32
 *         | key check 16 (HMAC-SHA256(key, "UCBK key check"), truncated: tells a wrong password from damage)
 * chunk*  length u32 | AES-256-GCM(ciphertext + 16-byte tag)
 * ```
 * Chunk `i` uses the nonce `prefix || i` (big-endian u32) and the additional data `header || last`, where `last` is 1 only
 * on the final chunk (possibly empty). Reordering, dropping or appending chunks, or cutting the file after a chunk,
 * fails a tag check; a fresh random salt and prefix per backup make every key and nonce unique.
 */
object BackupCrypto {
    val MAGIC = byteArrayOf('U'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    const val VERSION = 1
    private const val KDF_PBKDF2_SHA256 = 1

    /** OWASP's 2023 figure for PBKDF2-HMAC-SHA256; also the floor accepted on import. */
    const val ITERATIONS = 210_000

    /** Refuses headers that would make the import spin for minutes. */
    private const val MAX_ITERATIONS = 10_000_000
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 8
    private const val KEY_BITS = 256
    private const val TAG_BYTES = 16
    const val CHUNK_BYTES = 64 * 1024
    private const val MAX_CHUNK_BYTES = 1024 * 1024
    private const val CHECK_BYTES = 16
    private const val HEADER_BYTES = 4 + 1 + 1 + 4 + SALT_BYTES + PREFIX_BYTES + 4 + CHECK_BYTES
    private val CHECK_LABEL = "UCBK key check".toByteArray()

    /** Wraps [out]: what is written is encrypted with a key derived from [password]. Close it to write the last chunk. */
    fun encrypt(out: OutputStream, password: CharArray, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): OutputStream {
        require(iterations >= ITERATIONS) { "at least $ITERATIONS iterations" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val key = deriveKey(password, salt, iterations)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION.toByte()).put(KDF_PBKDF2_SHA256.toByte())
            .putInt(iterations).put(salt).put(prefix).putInt(CHUNK_BYTES).put(keyCheck(key)).array()
        out.write(header)
        return EncryptingStream(out, key, prefix, header)
    }

    /**
     * Reads the header from [input] (the magic included) and returns the decrypted stream. A wrong password fails the
     * header's key check ([BackupException.Reason.WRONG_PASSWORD]); a failed chunk is corruption.
     */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER_BYTES)
        try {
            data.readFully(header)
        } catch (e: EOFException) {
            throw BackupException(BackupException.Reason.CORRUPT, "truncated header", e)
        }
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) throw BackupException(BackupException.Reason.NOT_A_BACKUP, "not an encrypted backup")
        val version = buffer.get().toInt()
        val kdf = buffer.get().toInt()
        if (version > VERSION || kdf != KDF_PBKDF2_SHA256) throw BackupException(BackupException.Reason.TOO_NEW, "envelope $version/$kdf")
        val iterations = buffer.int
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val prefix = ByteArray(PREFIX_BYTES).also { buffer.get(it) }
        val chunk = buffer.int
        val check = ByteArray(CHECK_BYTES).also { buffer.get(it) }
        if (iterations !in ITERATIONS..MAX_ITERATIONS || chunk !in 1..MAX_CHUNK_BYTES) corrupt("bad header values")
        val key = deriveKey(password, salt, iterations)
        if (!MessageDigest.isEqual(check, keyCheck(key))) throw BackupException(BackupException.Reason.WRONG_PASSWORD, "wrong password")
        return DecryptingStream(data, key, prefix, header, chunk)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private fun keyCheck(key: SecretKey): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.encoded, "HmacSHA256")) }.doFinal(CHECK_LABEL).copyOf(CHECK_BYTES)

    private fun cipher(mode: Int, key: SecretKey, prefix: ByteArray, counter: Int, header: ByteArray, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(PREFIX_BYTES + 4).put(prefix).putInt(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(header)
            updateAAD(byteArrayOf(if (last) 1 else 0))
        }
    }

    private class EncryptingStream(private val out: OutputStream, private val key: SecretKey, private val prefix: ByteArray, private val header: ByteArray) :
        OutputStream() {
        private val pending = ByteArray(CHUNK_BYTES)
        private var size = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var from = off
            var left = len
            while (left > 0) {
                // A full chunk is only sealed once more data arrives, so the final chunk is always flagged as last.
                if (size == CHUNK_BYTES) seal(last = false)
                val n = minOf(left, CHUNK_BYTES - size)
                System.arraycopy(b, from, pending, size, n)
                size += n
                from += n
                left -= n
            }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            seal(last = true)
            closed = true
            out.close()
        }

        private fun seal(last: Boolean) {
            check(counter != -1) { "too many chunks" }
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, counter++, header, last).doFinal(pending, 0, size)
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
            out.write(sealed)
            size = 0
        }
    }

    private class DecryptingStream(
        private val input: DataInputStream,
        private val key: SecretKey,
        private val prefix: ByteArray,
        private val header: ByteArray,
        private val chunk: Int
    ) : InputStream() {
        private var current = ByteArray(0)
        private var position = 0
        private var counter = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == current.size) {
                if (finished) return -1
                next()
            }
            val n = minOf(len, current.size - position)
            System.arraycopy(current, position, b, off, n)
            position += n
            return n
        }

        override fun close() = input.close()

        private fun next() {
            val length = try {
                input.readInt()
            } catch (e: EOFException) {
                corrupt("truncated after chunk $counter", e)
            }
            if (length !in TAG_BYTES..chunk + TAG_BYTES) corrupt("bad chunk length")
            val sealed = ByteArray(length)
            try {
                input.readFully(sealed)
            } catch (e: EOFException) {
                corrupt("truncated chunk $counter", e)
            }
            // The last flag is not stored: try "more follows" first, then "last" (one extra tag check on the final chunk).
            current = open(sealed, last = false) ?: open(sealed, last = true)?.also { finished = true }
                ?: corrupt("chunk $counter failed its check")
            position = 0
            counter++
            if (finished && input.read() != -1) corrupt("data after the last chunk")
        }

        private fun open(sealed: ByteArray, last: Boolean): ByteArray? = try {
            cipher(Cipher.DECRYPT_MODE, key, prefix, counter, header, last).doFinal(sealed)
        } catch (_: AEADBadTagException) {
            null
        } catch (e: GeneralSecurityException) {
            corrupt("decryption failed", e)
        }
    }

    private fun corrupt(message: String, cause: Throwable? = null): Nothing = throw BackupException(BackupException.Reason.CORRUPT, message, cause)
}
