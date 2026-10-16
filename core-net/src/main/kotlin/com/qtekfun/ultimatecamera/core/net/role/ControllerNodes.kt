package com.qtekfun.ultimatecamera.core.net.role

import com.qtekfun.ultimatecamera.core.net.clock.CommonClockView
import com.qtekfun.ultimatecamera.core.net.clock.FrameRate
import com.qtekfun.ultimatecamera.core.net.clock.MonotonicClock
import com.qtekfun.ultimatecamera.core.net.clock.ScheduledStart
import com.qtekfun.ultimatecamera.core.net.control.CameraStatus
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.RecordingState
import java.util.UUID

/** One take requested from several cameras: the same [recordingId] and common start time on each. */
data class TakeRequest(val recordingId: String, val startAtCommonNanos: Long?, val commandIds: Map<String, String>)

/** A camera as the master's UI shows it. */
data class CameraView(val id: String, val link: LinkState, val status: CameraStatus?, val rttNanos: Long?, val pendingCommands: Int)

/**
 * Master role (REQ-NET-003, REQ-NET-005): one [ControllerLink] per paired camera, the common clock source and the
 * simultaneous start. Pure logic like the links; the driver dials, runs the clock server and calls [tick].
 */
class MasterNode(
    private val clock: MonotonicClock,
    /** The master's own common clock (a SourceClock). */
    private val commonClock: CommonClockView,
    private val linkFactory: (cameraId: String) -> ControllerLink,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val links = LinkedHashMap<String, ControllerLink>()

    fun addCamera(cameraId: String): ControllerLink = links.getOrPut(cameraId) { linkFactory(cameraId).also { it.start() } }

    fun removeCamera(cameraId: String) {
        links.remove(cameraId)?.stop()
    }

    fun link(cameraId: String): ControllerLink? = links[cameraId]

    val cameras: List<CameraView> get() = links.values.map { CameraView(it.cameraId, it.state, it.status, it.rttNanos, it.pendingCommands) }

    /**
     * Starts take on every camera at the same common time: now + a margin from the worst measured round trip, aligned
     * to a frame boundary of [rate] when given. Disconnected cameras get the command when they come back (they then
     * start late and say by how much in the ack).
     */
    fun startAll(rate: FrameRate? = null, utcOffsetSeconds: Int = 0): TakeRequest {
        val now = requireNotNull(commonClock.toCommon(clock.nanos())) { "the master is the clock source and is always synced" }
        val margin = ScheduledStart.margin(links.values.mapNotNull { it.rttNanos })
        val at = ScheduledStart.plan(now, margin, rate, utcOffsetSeconds)
        val recordingId = newId()
        val ids = links.values.associate { it.cameraId to it.submit(CommandBody.StartRecording(recordingId, at)) }
        return TakeRequest(recordingId, at, ids)
    }

    /** Stops [recordingId] (null: whatever each camera records) on every camera, at once. */
    fun stopAll(recordingId: String? = null): Map<String, String> = links.values.associate { it.cameraId to it.submit(CommandBody.StopRecording(recordingId)) }

    /** Sends the same setting to every camera (e.g. a shared white balance). */
    fun applyToAll(body: CommandBody): Map<String, String> = links.values.associate { it.cameraId to it.submit(body) }

    /** True when any camera records or has a start scheduled. */
    val anyRecording: Boolean get() = links.values.any { it.status?.recording == RecordingState.RECORDING || it.status?.recording == RecordingState.SCHEDULED }

    fun tick() = links.values.forEach { it.tick() }

    fun stop() = links.values.forEach { it.stop() }
}

/**
 * Monitor role (REQ-NET-003): controls one camera; starts and stops its recording, sets exposure, focus, white balance,
 * profile and LUT. In a camera + monitor pair without master the monitor is the clock source, so starts can still be
 * scheduled ([commonClock] synced); with no common clock they start on arrival.
 */
class MonitorNode(
    private val clock: MonotonicClock,
    private val commonClock: CommonClockView?,
    val link: ControllerLink,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    fun start() = link.start()

    fun stop() = link.stop()

    fun tick() = link.tick()

    /** Starts a take; returns its recording id. */
    fun startRecording(rate: FrameRate? = null, utcOffsetSeconds: Int = 0): String {
        val recordingId = newId()
        val now = commonClock?.toCommon(clock.nanos())
        val at = now?.let { ScheduledStart.plan(it, ScheduledStart.margin(listOfNotNull(link.rttNanos)), rate, utcOffsetSeconds) }
        link.submit(CommandBody.StartRecording(recordingId, at))
        return recordingId
    }

    /** Stops what the camera records (the take this monitor started or one started on the camera). */
    fun stopRecording(): String = link.submit(CommandBody.StopRecording(link.status?.recordingId))

    fun setExposure(body: CommandBody.SetExposure) = link.submit(body)

    fun setFocus(body: CommandBody.SetFocus) = link.submit(body)

    fun setWhiteBalance(body: CommandBody.SetWhiteBalance) = link.submit(body)

    fun selectProfile(profileId: String) = link.submit(CommandBody.SelectProfile(profileId))

    fun selectLut(lutId: String?) = link.submit(CommandBody.SelectLut(lutId))
}
