package com.qtekfun.ultimatecamera.feature.multicam.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyProtector] on a non-exportable AES-256-GCM key in Android Keystore (D39): the key cannot be read out of the
 * phone, so a copied data folder does not reveal the pair keys. Output: 12-byte IV + ciphertext with a 128-bit tag.
 * Verified on phones (docs/TESTING.md), not in unit tests.
 */
class KeystoreProtector private constructor() : KeyProtector {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw GeneralSecurityException("too short")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "ultimatecamera_pair_keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEY_BITS = 256

        /** A protector that passed a seal/open round trip, or null where Keystore fails (then the plain file is used). */
        @Suppress("TooGenericExceptionCaught") // Vendor Keystore bugs throw anything (ProviderException, NPE, ...).
        fun createOrNull(): KeyProtector? = try {
            val protector = KeystoreProtector()
            val probe = byteArrayOf(1, 2, 3, 4)
            if (protector.open(protector.seal(probe)).contentEquals(probe)) protector else null
        } catch (_: Exception) {
            null
        }
    }
}
