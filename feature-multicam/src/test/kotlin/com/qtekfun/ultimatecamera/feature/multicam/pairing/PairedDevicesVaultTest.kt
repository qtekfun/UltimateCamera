package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.pairing.B64
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevice
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDeviceStore
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevicesFile
import com.qtekfun.ultimatecamera.core.net.pairing.PairedDevicesLoad
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Software AES-GCM standing in for Android Keystore. */
private class SoftProtector(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : KeyProtector {
    var broken = false

    /** What a real Keystore throws now and then besides GeneralSecurityException (D77). */
    var unchecked: RuntimeException? = null

    override fun seal(plain: ByteArray): ByteArray {
        unchecked?.let { throw it }
        if (broken) throw GeneralSecurityException("keystore gone")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        unchecked?.let { throw it }
        if (broken) throw GeneralSecurityException("keystore gone")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

class PairedDevicesVaultTest {
    @get:Rule
    val temp = TemporaryFolder(File("build/tmp/vault-test").apply { mkdirs() })

    private fun device(name: String = "Pixel") = PairedDevice(
        id = Secrets.newDeviceId(),
        name = name,
        role = DeviceRole.CAMERA,
        key = B64.encode(Secrets.randomBytes(Secrets.KEY_BYTES)),
        pairedAtEpochMs = 1L
    )

    private fun content() = PairedDevicesFile(ownId = Secrets.newDeviceId(), devices = listOf(device()))

    @Test
    fun sealedRoundTripLeavesNoKeyInClear() {
        val dir = temp.newFolder()
        val protector = SoftProtector()
        val vault = PairedDevicesVault(dir, protector)
        val saved = content()
        vault.save(saved)
        val sealed = File(dir, PairedDevicesVault.SEALED_FILE_NAME)
        assertTrue(sealed.exists())
        assertFalse(File(dir, PairedDeviceStore.FILE_NAME).exists())
        assertFalse(String(sealed.readBytes(), Charsets.ISO_8859_1).contains(saved.devices[0].key))
        val loaded = PairedDevicesVault(dir, protector).load() as PairedDevicesLoad.Ok
        assertEquals(saved, loaded.file)
        assertTrue(vault.protected)
    }

    @Test
    fun plainFileIsMigratedToSealed() {
        val dir = temp.newFolder()
        val saved = content()
        PairedDeviceStore(File(dir, PairedDeviceStore.FILE_NAME)).save(saved)
        val protector = SoftProtector()
        val first = PairedDevicesVault(dir, protector).load() as PairedDevicesLoad.Ok
        assertEquals(saved, first.file)
        assertFalse(File(dir, PairedDeviceStore.FILE_NAME).exists())
        assertTrue(File(dir, PairedDevicesVault.SEALED_FILE_NAME).exists())
        assertEquals(saved, (PairedDevicesVault(dir, protector).load() as PairedDevicesLoad.Ok).file)
    }

    @Test
    fun withoutKeystoreThePlainFileIsUsed() {
        val dir = temp.newFolder()
        val vault = PairedDevicesVault(dir, null)
        val saved = content()
        vault.save(saved)
        assertFalse(vault.protected)
        assertTrue(File(dir, PairedDeviceStore.FILE_NAME).exists())
        assertEquals(saved, (PairedDevicesVault(dir, null).load() as PairedDevicesLoad.Ok).file)
    }

    @Test
    fun aKeystoreFailureOnSaveFallsBackToPlain() {
        val dir = temp.newFolder()
        val protector = SoftProtector()
        val vault = PairedDevicesVault(dir, protector)
        vault.save(content())
        protector.broken = true
        val saved = content()
        vault.save(saved)
        assertFalse(vault.protected)
        assertFalse(File(dir, PairedDevicesVault.SEALED_FILE_NAME).exists())
        assertEquals(saved, (PairedDevicesVault(dir, null).load() as PairedDevicesLoad.Ok).file)
    }

    /**
     * Regression (D77): Keystore also throws unchecked ProviderException; it escaped save() (only
     * GeneralSecurityException was caught) and crashed the pairing that was storing its new key.
     */
    @Test
    fun anUncheckedKeystoreFailureOnSaveFallsBackToPlain() {
        val dir = temp.newFolder()
        val protector = SoftProtector()
        val vault = PairedDevicesVault(dir, protector)
        vault.save(content())
        protector.unchecked = ProviderException("Keystore operation failed")
        val saved = content()
        vault.save(saved)
        assertFalse(vault.protected)
        assertEquals(saved, (PairedDevicesVault(dir, null).load() as PairedDevicesLoad.Ok).file)
        // Through the repository, as a pairing stores its key: no exception reaches the caller.
        val repo = PairedDevicesRepository(PairedDevicesVault(temp.newFolder(), SoftProtector().apply { unchecked = ProviderException("busy") })) { 1L }
        repo.remove("nothing")
        assertTrue(repo.devices.value.isEmpty())
    }

    /** Regression (D77): an unchecked Keystore failure while unsealing at start set nothing aside and crashed. */
    @Test
    fun anUncheckedKeystoreFailureOnLoadResetsInsteadOfCrashing() {
        val dir = temp.newFolder()
        val protector = SoftProtector()
        PairedDevicesVault(dir, protector).save(content())
        protector.unchecked = ProviderException("Keystore operation failed")
        val load = PairedDevicesVault(dir, protector).load()
        assertTrue(load is PairedDevicesLoad.Recovered)
        assertTrue(File(dir, PairedDevicesVault.SEALED_FILE_NAME + ".corrupt").exists())
    }

    @Test
    fun aSealedFileFromAnotherKeyIsSetAsideAndPairingsReset() {
        val dir = temp.newFolder()
        PairedDevicesVault(dir, SoftProtector()).save(content())
        val load = PairedDevicesVault(dir, SoftProtector()).load()
        assertTrue(load is PairedDevicesLoad.Recovered)
        assertTrue(load.file.devices.isEmpty())
        assertTrue(File(dir, PairedDevicesVault.SEALED_FILE_NAME + ".corrupt").exists())
    }

    @Test
    fun repositoryKeepsItsOwnIdAndStoresChanges() {
        val dir = temp.newFolder()
        val protector = SoftProtector()
        val repo = PairedDevicesRepository(PairedDevicesVault(dir, protector)) { 5_000L }
        val id = repo.ownId
        val again = PairedDevicesRepository(PairedDevicesVault(dir, protector)) { 6_000L }
        assertEquals(id, again.ownId)
        assertTrue(again.devices.value.isEmpty())
    }
}
