package com.qtekfun.ultimatecamera.core.net.transfer

import com.qtekfun.ultimatecamera.core.net.control.ClientHandshake
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.HandshakeException
import com.qtekfun.ultimatecamera.core.net.control.HandshakeMode
import com.qtekfun.ultimatecamera.core.net.control.Keyring
import com.qtekfun.ultimatecamera.core.net.control.LocalIdentity
import com.qtekfun.ultimatecamera.core.net.control.ServerHandshake
import com.qtekfun.ultimatecamera.core.net.pairing.Secrets
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProxyTransferTest {
    @get:Rule val folder = TemporaryFolder()

    private val camera = LocalIdentity(Secrets.newDeviceId(), "Camera", DeviceRole.CAMERA)
    private val monitor = LocalIdentity(Secrets.newDeviceId(), "Monitor", DeviceRole.MONITOR)
    private val pairKey = Secrets.randomBytes(Secrets.KEY_BYTES)
    private val keyring = object : Keyring {
        override fun pairKey(deviceId: String) = if (deviceId == monitor.deviceId) pairKey else null

        override fun pairingToken(): ByteArray? = null
    }

    /** 2.5 chunks, so a transfer has several chunks and a short last one. */
    private val content = Random(7).nextBytes(ProxyTransferProtocol.CHUNK_BYTES * 5 / 2)

    private inner class FakeSource : ProxySource {
        @Volatile override var busy = false

        /** Chunks the camera may still send before it "starts recording". */
        @Volatile var chunksBeforeRec = Int.MAX_VALUE

        override fun list() = listOf(ProxyEntry("UC_20261017_120000_CAM0_T001_proxy.mp4", content.size.toLong()))

        override fun open(name: String): InputStream? = if (name == list().first().name) CountingStream(ByteArrayInputStream(content)) else null

        private inner class CountingStream(private val inner: InputStream) : InputStream() {
            override fun read(): Int = inner.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (chunksBeforeRec-- <= 0) busy = true
                return inner.read(b, off, len)
            }
        }
    }

    private fun withServer(source: ProxySource, block: suspend (port: Int) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val bound = CompletableDeferred<Int>()
            val server = launch(Dispatchers.IO) {
                ProxyTransferServer(source).serve(0, { ServerHandshake(camera, keyring) }) { bound.complete(it) }
            }
            block(bound.await())
            server.cancelAndJoin()
        }
    }

    private suspend fun connect(port: Int) =
        ProxyTransferClient.connect("127.0.0.1", port, ClientHandshake(monitor, HandshakeMode.RESUME, camera.deviceId, pairKey))

    @Test
    fun listsAndDownloadsWithAMatchingHash() = withServer(FakeSource()) { port ->
        val client = connect(port)
        withContext(Dispatchers.IO) {
            val entries = client.list()
            assertEquals(1, entries.size)
            val target = folder.newFile().also { it.delete() }
            val result = client.download(entries.first().name, target)
            val expected = ProxyTransferProtocol.hex(MessageDigest.getInstance("SHA-256").digest(content))
            assertEquals(DownloadResult.Complete(content.size.toLong(), expected), result)
            assertArrayEquals(content, target.readBytes())
            client.close()
        }
    }

    @Test
    fun recordingPausesTheTransferAndItResumesFromTheBytesHeld() {
        val source = FakeSource().apply { chunksBeforeRec = 1 }
        withServer(source) { port ->
            val client = connect(port)
            withContext(Dispatchers.IO) {
                val target = folder.newFile().also { it.delete() }
                val name = client.list().first().name
                val paused = client.download(name, target)
                assertTrue("$paused", paused is DownloadResult.Paused)
                val held = (paused as DownloadResult.Paused).bytes
                assertTrue(held in 1 until content.size.toLong())
                assertEquals(held, target.length())
                // The take ended: the same connection resumes and the hash still covers the whole file.
                source.chunksBeforeRec = Int.MAX_VALUE
                source.busy = false
                assertTrue(client.download(name, target) is DownloadResult.Complete)
                assertArrayEquals(content, target.readBytes())
                client.close()
            }
        }
    }

    @Test
    fun aCorruptLocalPrefixIsDetectedAndDeleted() = withServer(FakeSource()) { port ->
        val client = connect(port)
        withContext(Dispatchers.IO) {
            val target = folder.newFile()
            target.writeBytes(ByteArray(1000) { 0x55 })
            assertEquals(DownloadResult.Corrupt, client.download(client.list().first().name, target))
            assertFalse(target.exists())
            client.close()
        }
    }

    @Test
    fun unknownNamesFailWithoutClosingTheConnection() = withServer(FakeSource()) { port ->
        val client = connect(port)
        withContext(Dispatchers.IO) {
            val result = client.download("../../etc/passwd", folder.newFile().also { it.delete() })
            assertTrue("$result", result is DownloadResult.Failed)
            assertEquals(1, client.list().size)
            client.close()
        }
    }

    @Test
    fun anUnpairedDeviceIsRefused() = withServer(FakeSource()) { port ->
        val stranger = LocalIdentity(Secrets.newDeviceId(), "Stranger", DeviceRole.MONITOR)
        val error = runCatching {
            ProxyTransferClient.connect("127.0.0.1", port, ClientHandshake(stranger, HandshakeMode.RESUME, camera.deviceId, pairKey))
        }.exceptionOrNull()
        assertTrue("$error", error is HandshakeException || error is IOException)
    }

    @Test
    fun framesRoundTrip() {
        val chunk = TransferReply.Chunk(42, byteArrayOf(1, 2, 3, 9), 3)
        val decoded = ProxyTransferProtocol.decodeReply(ProxyTransferProtocol.encode(chunk)) as TransferReply.Chunk
        assertEquals(42L, decoded.offset)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.data.copyOf(decoded.length))
        val get = TransferRequest.Get("a_proxy.mp4", 7)
        assertEquals(get, ProxyTransferProtocol.decodeRequest(ProxyTransferProtocol.encode(get)))
        assertEquals(TransferReply.Paused, ProxyTransferProtocol.decodeReply(ProxyTransferProtocol.encode(TransferReply.Paused)))
        assertTrue(runCatching { ProxyTransferProtocol.decodeReply(byteArrayOf(0x7f)) }.exceptionOrNull() is IOException)
    }
}
