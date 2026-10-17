package com.qtekfun.ultimatecamera.feature.multicam.monitor

import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.pairing.Endpoint
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import com.qtekfun.ultimatecamera.core.net.transfer.DownloadResult
import com.qtekfun.ultimatecamera.core.net.transfer.ProxyEntry
import com.qtekfun.ultimatecamera.core.net.transfer.ProxySource
import com.qtekfun.ultimatecamera.core.net.transfer.ProxyTransferServer
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProxyDownloadsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun statesFollowWhatIsOnDisk() {
        val entry = ProxyEntry("a_proxy.mp4", 100)
        assertEquals(ProxyItemState.REMOTE, ProxyListMath.stateOf(entry, held = 0, verified = false))
        assertEquals(ProxyItemState.PARTIAL, ProxyListMath.stateOf(entry, held = 40, verified = false))
        // A full-size file is only "done" once its hash was checked here.
        assertEquals(ProxyItemState.PARTIAL, ProxyListMath.stateOf(entry, held = 100, verified = false))
        assertEquals(ProxyItemState.DONE, ProxyListMath.stateOf(entry, held = 100, verified = true))
        assertEquals(ProxyItemState.PAUSED, ProxyListMath.stateOf(DownloadResult.Paused(3)))
        assertEquals(ProxyItemState.CORRUPT, ProxyListMath.stateOf(DownloadResult.Corrupt))
    }

    @Test
    fun onlyPlainFileNamesAreAccepted() {
        assertTrue(ProxyListMath.safeName("UC_20261017_120000_CAM0_T001_proxy.mp4"))
        assertFalse(ProxyListMath.safeName("../secrets"))
        assertFalse(ProxyListMath.safeName("a/b.mp4"))
        assertFalse(ProxyListMath.safeName(".hidden"))
        assertEquals("Pixel_8_Pro", ProxyFolder.folderName("Pixel 8/Pro"))
        assertEquals("camera", ProxyFolder.folderName(""))
    }

    @Test
    fun listsAndDownloadsFromACameraOverLoopback() = runBlocking {
        withTimeout(30_000) {
            val camera = LocalIdentity(Secrets.newDeviceId(), "Camera", DeviceRole.CAMERA)
            val monitor = LocalIdentity(Secrets.newDeviceId(), "Monitor", DeviceRole.MONITOR)
            val key = Secrets.randomBytes(Secrets.KEY_BYTES)
            val content = Random(3).nextBytes(300_000)
            val name = "UC_20261017_120000_CAM0_T001_proxy.mp4"
            val source = object : ProxySource {
                override fun list() = listOf(ProxyEntry(name, content.size.toLong()))

                override fun open(name: String): InputStream = ByteArrayInputStream(content)

                override val busy = false
            }
            val keyring = object : Keyring {
                override fun pairKey(deviceId: String) = if (deviceId == monitor.deviceId) key else null

                override fun pairingToken(): ByteArray? = null
            }
            val bound = CompletableDeferred<Int>()
            val server = launch(Dispatchers.IO) { ProxyTransferServer(source).serve(0, { ServerHandshake(camera, keyring) }) { bound.complete(it) } }
            val port = bound.await()
            val dir = folder.newFolder()
            val target = RemoteCameraTarget(camera.deviceId, camera.name, key) { listOf(Endpoint("127.0.0.1", port)) }
            val downloads = ProxyDownloads(target, monitor, dir, port)

            downloads.refresh(this)
            val listed = downloads.state.first { it.items.isNotEmpty() || it.unreachable }
            assertEquals(ProxyItemState.REMOTE, listed.items.single().state)

            downloads.download(this, name)
            val done = downloads.state.first { ui -> ui.items.single().state.let { it != ProxyItemState.REMOTE && it != ProxyItemState.DOWNLOADING } }
            assertEquals(ProxyItemState.DONE, done.items.single().state)
            assertArrayEquals(content, dir.resolve(name).readBytes())
            server.cancelAndJoin()
        }
    }
}
