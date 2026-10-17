package com.qtekfun.ultimatecamera.feature.multicam.monitor

import android.os.SystemClock
import android.util.Log
import com.qtekfun.ultimatecamera.core.net.clock.ActiveCommonClock
import com.qtekfun.ultimatecamera.core.net.clock.ClockServer
import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.clock.SourceClock
import com.qtekfun.ultimatecamera.core.net.control.Session
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The common clock of the session on the controlling phone (REQ-NET-005, D31, D58): the master, or the monitor of a
 * camera + monitor pair, is the clock source. While the monitor or master screens are open it answers the cameras'
 * clock clients on UDP [port] (47803; a camera dials the address its controller came from) and publishes its
 * [SourceClock] as [ActiveCommonClock], so "REC" and "REC all" are planned on the clock the cameras sync to.
 *
 * Only cameras with a live control session get answers: each control session's clock key ([onSession]) is added, and
 * replaces that camera's previous one. A port that cannot be bound leaves the session without a common clock (starts
 * on arrival, as before), never a crash.
 */
class SessionClockSource(
    /** The same clock as [ActiveCommonClock]'s readers on this phone (`elapsedRealtimeNanos`). */
    monotonic: MonotonicClock = MonotonicClock { SystemClock.elapsedRealtimeNanos() },
    wallNanos: () -> Long = { System.currentTimeMillis() * NANOS_PER_MS },
    private val port: Int = ClockServer.DEFAULT_PORT,
    private val log: (String) -> Unit = { Log.w(TAG, it) }
) {
    /** The session's common time: wall time once, then the monotonic clock (never jumps with NTP or the user). */
    val clock = SourceClock(monotonic, wallNanos())
    private val server = ClockServer(clock)
    private val keys = ConcurrentHashMap<String, ByteArray>()

    /** The UDP port bound (useful with port 0 in tests); 0 while not serving. */
    @Volatile var boundPort = 0
        private set

    /** A control session with camera [cameraId] opened: its clock packets are answered from now on. */
    fun onSession(cameraId: String, session: Session) {
        val key = session.clockKey.copyOf()
        server.addKey(key)
        keys.put(cameraId, key)?.takeUnless { it.contentEquals(key) }?.let(server::removeKey)
    }

    /**
     * Serves until [scope] or the returned job is cancelled; [ActiveCommonClock] is this clock from the first bind until
     * then. A socket that fails (Wi-Fi dropped, port taken) is retried every [retryMs] on the same clock, so the common
     * time the cameras already follow does not change.
     */
    @Suppress("TooGenericExceptionCaught") // Network boundary: without a clock server the takes start on arrival.
    fun launchIn(scope: CoroutineScope, retryMs: Long = RETRY_MS): Job = scope.launch {
        try {
            while (isActive) {
                try {
                    server.serve(port) { bound ->
                        boundPort = bound
                        ActiveCommonClock.current = clock
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("clock server: ${e.message}")
                }
                boundPort = 0
                delay(retryMs)
            }
        } finally {
            boundPort = 0
            if (ActiveCommonClock.current === clock) ActiveCommonClock.current = null
        }
    }

    private companion object {
        const val TAG = "SessionClock"
        const val NANOS_PER_MS = 1_000_000L
        const val RETRY_MS = 2_000L
    }
}
