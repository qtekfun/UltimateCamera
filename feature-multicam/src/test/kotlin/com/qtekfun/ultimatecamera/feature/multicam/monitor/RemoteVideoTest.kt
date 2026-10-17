package com.qtekfun.ultimatecamera.feature.multicam.monitor

import com.qtekfun.ultimatecamera.core.net.monitor.QueueSignallingTransport
import com.qtekfun.ultimatecamera.core.net.role.VideoRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVideoTest {
    private fun route() = VideoRoute.Control(QueueSignallingTransport("cam") { true })

    @Test
    fun aSecurityExceptionFromLibwebrtcMeansNoVideoNotACrash() {
        // libwebrtc's network monitor throws without ACCESS_NETWORK_STATE; the monitor screen crashed here before D81.
        val route = route()
        assertNull(openVideo<Any>(route) { throw SecurityException("ACCESS_NETWORK_STATE") })
        assertTrue(route.transport.closed.value)
    }

    @Test
    fun aMissingNativeLibraryOrAnyRuntimeFailureMeansNoVideo() {
        val missing = route()
        assertNull(openVideo<Any>(missing) { throw UnsatisfiedLinkError("libjingle_peerconnection_so") })
        assertTrue(missing.transport.closed.value)
        val broken = route()
        assertNull(openVideo<Any>(broken) { throw IllegalArgumentException("bad") })
        assertTrue(broken.transport.closed.value)
    }

    @Test
    fun aWorkingReceiverIsReturnedAndKeepsItsRoute() {
        val route = route()
        assertEquals("receiver", openVideo(route) { "receiver" })
        assertFalse(route.transport.closed.value)
        assertNull(openVideo(VideoRoute.None) { null })
    }
}
