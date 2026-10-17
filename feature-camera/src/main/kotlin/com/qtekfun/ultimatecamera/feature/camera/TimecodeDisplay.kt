package com.qtekfun.ultimatecamera.feature.camera

import android.os.SystemClock
import com.qtekfun.ultimatecamera.core.net.clock.ActiveCommonClock
import com.qtekfun.ultimatecamera.core.net.clock.CameraClockBridge
import com.qtekfun.ultimatecamera.core.net.clock.FrameRate
import com.qtekfun.ultimatecamera.core.net.clock.SyncedStart
import com.qtekfun.ultimatecamera.core.net.clock.TimeOfDay
import com.qtekfun.ultimatecamera.core.recording.CameraController
import com.qtekfun.ultimatecamera.core.recording.TakeTimecode
import com.qtekfun.ultimatecamera.core.settings.CaptureModeRepository
import com.qtekfun.ultimatecamera.core.settings.TimecodeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

/**
 * One time-of-day source for the takes and the screen (D58): the session's common clock when this phone is in a
 * multicamera session ([ActiveCommonClock]), else the phone clock. Installs itself as [CameraController.timeOfDay], so
 * each take's WAV is stamped from it, and [label] is the on-screen timecode while "Timecode: time of day" is chosen
 * (null in record-run mode, the default: the screen then shows the take's running time).
 */
class TimecodeDisplay(modes: CaptureModeRepository?, scope: CoroutineScope, private val controller: CameraController, private val fps: () -> Int) {
    private val bridge = CameraClockBridge(
        local = { SystemClock.elapsedRealtimeNanos() },
        camera = { controller.cameraClockNanos() ?: SystemClock.elapsedRealtimeNanos() }
    )
    private val timeOfDay = TimeOfDay(bridge, common = { ActiveCommonClock.current }, wallNanos = { System.currentTimeMillis() * NANOS_PER_MS })

    init {
        controller.timeOfDay = timeOfDay::epochNanos
    }

    /** The time-of-day timecode now at the current frame rate, `HH:MM:SS:FF` (`;FF` at 29.97/59.94 drop-frame). */
    fun now(): String {
        val epoch = timeOfDay.epochNanos(bridge.cameraNow())
        return SyncedStart.timecode(epoch, FrameRate(fps().takeIf { it > 0 } ?: DEFAULT_FPS), TakeTimecode.utcOffsetSeconds(epoch)).toString()
    }

    val label: StateFlow<String?> = channelFlow {
        (modes?.captureModePrefs?.map { it.timecode } ?: flowOf(TimecodeMode.RECORD_RUN)).distinctUntilChanged().collectLatest { mode ->
            if (mode != TimecodeMode.TIME_OF_DAY) {
                send(null)
                return@collectLatest
            }
            while (isActive) {
                send(now())
                delay(TICK_MS)
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val DEFAULT_FPS = 30

        /** Same 15 Hz as the record-run display. */
        const val TICK_MS = 66L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
