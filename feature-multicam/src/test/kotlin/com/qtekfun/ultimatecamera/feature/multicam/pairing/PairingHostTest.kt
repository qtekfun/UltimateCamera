package com.qtekfun.ultimatecamera.feature.multicam.pairing

import com.qtekfun.ultimatecamera.core.net.control.ControlProtocol
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceAdvert
import com.qtekfun.ultimatecamera.core.net.discovery.ServiceDiscovery
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.PairingParseResult
import com.qtekfun.ultimatecamera.core.net.pairing.PairingPayload
import com.qtekfun.ultimatecamera.feature.multicam.session.SessionRoles
import java.io.Closeable
import java.security.ProviderException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The showing side of pairing over loopback: any phone may scan it (D80), and a key that cannot be stored is an error. */
class PairingHostTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private object NoDiscovery : ServiceDiscovery {
        override fun advertise(advert: ServiceAdvert, onFailed: (code: Int) -> Unit) = Closeable {}

        override fun browse(onEvent: (com.qtekfun.ultimatecamera.core.net.discovery.DiscoveryEvent) -> Unit) = Closeable {}
    }

    /** Seals the first document (the new own id), then fails as some vendors' Keystore does: a RuntimeException. */
    private class FailingProtector : KeyProtector {
        private var calls = 0

        override fun seal(plain: ByteArray): ByteArray = if (calls++ == 0) plain.reversedArray() else throw ProviderException("keystore broke")

        override fun open(sealed: ByteArray): ByteArray = sealed.reversedArray()
    }

    private fun repo(protector: KeyProtector? = null) = PairedDevicesRepository(PairedDevicesVault(temp.newFolder(), protector)) { 42L }

    private fun host(devices: PairedDevicesRepository, role: DeviceRole) = PairingHost(
        scope = scope,
        devices = devices,
        discovery = NoDiscovery,
        identity = { LocalIdentity(devices.ownId, "Shower", role) },
        // The QR carries the Wi-Fi address (loopback is never put in it); the test dials loopback at the same port.
        addresses = { listOf(LocalEndpoints.Candidate("wlan0", "192.168.1.50")) },
        window = PairingWindow()
    )

    private fun showing(host: PairingHost): PairingPayload = runBlocking {
        val shown = withTimeout(5_000) { host.state.first { it is HostState.Showing } } as HostState.Showing
        (PairingPayload.parse(shown.qrText, null) as PairingParseResult.Ok).payload
    }

    private fun PairingPayload.loopback() = listOf(Endpoint("127.0.0.1", endpoints.first().port))

    @Test
    fun aPhoneThatWasACameraCanScanAndBothStoreTheNeutralRole() = runBlocking {
        val shower = repo()
        val host = host(shower, SessionRoles.NEUTRAL)
        host.start()
        val payload = showing(host)
        val scanner = repo()
        // Before D80 the showing phone refused a scanner whose role was camera (ROLE_NOT_ALLOWED).
        val outcome = PairingClient(
            scanner
        ).pair(LocalIdentity(scanner.ownId, "Scanner", DeviceRole.CAMERA), payload.deviceId, payload.loopback(), payload.token)
        assertTrue(outcome.toString(), outcome is PairOutcome.Paired)
        val paired = withTimeout(5_000) { host.state.first { it is HostState.Paired } } as HostState.Paired
        assertEquals(HostState.Paired("Scanner", scanner.ownId), paired)
        val stored = shower.devices.value.single()
        assertEquals(SessionRoles.NEUTRAL, stored.role)
        // The showing phone can reach the scanner later (role choice from either side).
        assertEquals(listOf(Endpoint("127.0.0.1", ControlProtocol.DEFAULT_PORT)), stored.endpoints)
        assertEquals(SessionRoles.NEUTRAL, scanner.devices.value.single().role)
        host.stop()
    }

    /** A Keystore that breaks while the key is stored never crashes: the vault keeps the key in the plain file (D77, D39). */
    @Test
    fun aKeystoreThatBreaksWhileStoringFallsBackToThePlainFile() = runBlocking {
        val shower = repo(FailingProtector())
        val host = host(shower, SessionRoles.NEUTRAL)
        host.start()
        val payload = showing(host)
        val scanner = repo()
        PairingClient(scanner).pair(LocalIdentity(scanner.ownId, "Scanner", DeviceRole.MONITOR), payload.deviceId, payload.loopback(), payload.token)
        assertEquals(HostState.Paired("Scanner", scanner.ownId), withTimeout(5_000) { host.state.first { it is HostState.Failed || it is HostState.Paired } })
        assertEquals(scanner.ownId, shower.devices.value.single().id)
        assertFalse(shower.keysProtected)
        host.stop()
    }

    /** Same on the scanning side: paired, key in the plain file, no crash (D77). */
    @Test
    fun theScanningSideFallsBackToThePlainFileWhenKeystoreBreaks() = runBlocking {
        val shower = repo()
        val host = host(shower, SessionRoles.NEUTRAL)
        host.start()
        val payload = showing(host)
        val scanner = repo(FailingProtector())
        val outcome = PairingClient(
            scanner
        ).pair(LocalIdentity(scanner.ownId, "Scanner", DeviceRole.MONITOR), payload.deviceId, payload.loopback(), payload.token)
        assertTrue(outcome.toString(), outcome is PairOutcome.Paired)
        assertEquals(payload.deviceId, scanner.devices.value.single().id)
        assertFalse(scanner.keysProtected)
        host.stop()
    }
}
