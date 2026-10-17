package com.qtekfun.ultimatecamera.feature.multicam.monitor

import com.qtekfun.ultimatecamera.core.net.clock.ActiveCommonClock
import com.qtekfun.ultimatecamera.core.net.clock.ClockClient
import com.qtekfun.ultimatecamera.core.net.clock.ClockEstimator
import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.control.DeviceRole
import com.qtekfun.ultimatecamera.core.net.control.HandshakeMode
import com.qtekfun.ultimatecamera.core.net.control.Session
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controlling phone's clock source over loopback UDP: only cameras with a control session get the common time. */
class SessionClockSourceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nanos = MonotonicClock { System.nanoTime() }
    private val logs = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        scope.cancel()
        ActiveCommonClock.current = null
    }

    private fun source(port: Int = 0) = SessionClockSource(nanos, wallNanos = { 1_792_195_200_000_000_000L }, port = port, log = { logs += it })

    private fun session(clockKey: ByteArray) = Session("cam", "Cam", DeviceRole.CAMERA, 1, HandshakeMode.RESUME, ByteArray(32), ByteArray(32), clockKey, null)

    private fun key(fill: Int) = ByteArray(32) { fill.toByte() }

    private suspend fun SessionClockSource.awaitBound(): Int = withTimeout(5_000) {
        while (boundPort == 0) delay(5)
        boundPort
    }

    private fun exchange(port: Int, clockKey: ByteArray) = DatagramSocket().use { socket ->
        socket.soTimeout = 300
        ClockClient("127.0.0.1", port, clockKey, nanos, ClockEstimator()).exchange(socket, InetAddress.getLoopbackAddress(), 1)
    }

    @Test
    fun aCameraWithAControlSessionGetsTheCommonTimeOthersDoNot() = runBlocking {
        val source = source()
        val job = source.launchIn(scope)
        val port = source.awaitBound()
        assertSame("REC is planned on the clock the cameras sync to", source.clock, ActiveCommonClock.current)
        assertNull("no session yet: no answer", exchange(port, key(1)))
        source.onSession("cam", session(key(1)))
        val sample = exchange(port, key(1))
        assertNotNull(sample)
        assertTrue("the answer is the source's common time", sample!!.t1 >= 1_792_195_200_000_000_000L)
        // The camera reconnects: its new session key replaces the old one.
        source.onSession("cam", session(key(2)))
        assertNull(exchange(port, key(1)))
        assertNotNull(exchange(port, key(2)))
        source.onSession("cam", session(key(2))) // The same key again keeps it.
        assertNotNull(exchange(port, key(2)))
        job.cancelAndJoin()
        assertNull("leaving the monitor ends the common clock", ActiveCommonClock.current)
        assertEquals(0, source.boundPort)
    }

    @Test
    fun aClockServerThatCannotRunLeavesNoCommonClockAndNeverThrows() = runBlocking {
        val source = source(port = -1) // Not a port: binding throws.
        val job = source.launchIn(scope, retryMs = 10)
        withTimeout(5_000) { while (logs.size < 2) delay(5) }
        assertNull("takes start on arrival without a clock server", ActiveCommonClock.current)
        assertTrue(job.isActive) // Retrying, not crashed.
        job.cancelAndJoin()
    }

    @Test
    fun anotherClockIsNotClearedWhenTheSourceStops() = runBlocking {
        val source = source()
        val job = source.launchIn(scope)
        source.awaitBound()
        val other = source()
        ActiveCommonClock.current = other.clock
        job.cancelAndJoin()
        assertSame(other.clock, ActiveCommonClock.current)
    }
}
