package com.qtekfun.ultimatecamera.core.capabilities

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why a run ended before its plan did. */
enum class StopReason { BUDGET, THERMAL, CAMERA_ERRORS }

data class ProbeRun(
    val capabilities: DeviceCapabilities,
    /** Null when the plan ran to its end. */
    val stopReason: StopReason?,
    /** Probes that opened the camera (vendor retries included). */
    val sessionsRun: Int,
    val elapsedMs: Long
)

/** Called after every recorded result, for a progress bar ("3 of 8"). */
fun interface ProbeListener {
    fun onResult(done: Int, total: Int, result: ProbeResult)
}

/**
 * The OBS-style auto-configuration: runs the [ProbePlanner]'s plan through a [ProbeRunner] and records every result in
 * the [CapabilityStore] as it comes (D13: nothing is lost on a crash or a cancellation).
 *
 * - Results already in the store for this device and OS build are reused: an interrupted run resumes where it stopped.
 * - Implied outcomes ([Implications]) are recorded without opening the camera.
 * - Thermal guard: a probe starts only when the phone is at [ProbePolicy.maxStartThermalStatus] or cooler (it waits up
 *   to [ProbePolicy.thermalWaitMs]); a probe reaching [ProbePolicy.abortThermalStatus] ends the run; drops on a warm
 *   phone are recorded as thermal-limited and imply nothing.
 * - Budget: no probe starts if it would end after [ProbePolicy.budgetMs].
 * - Crash safety: an in-progress marker is saved before each probe opens the camera; if the app dies there, the next
 *   run records that configuration as [ProbeOutcome.CRASHED] and never retries it. A watchdog turns a probe that hangs
 *   into [ProbeOutcome.TIMEOUT].
 * - Cancellation: the results so far are saved, then the cancellation propagates.
 */
class ProbeEngine(
    private val runner: ProbeRunner,
    private val store: CapabilityStore,
    private val thermal: ThermalMonitor,
    private val clock: MonotonicClock,
    private val policy: ProbePolicy = ProbePolicy(),
    private val vendor: VendorExtension = NoVendorExtension,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val planner = ProbePlanner(policy)

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One loop with the stop rules side by side reads best.
    suspend fun run(
        device: AdvertisedDevice,
        cameraIds: List<String> = ProbePlanner.primaryCameraIds(device),
        resume: Boolean = true,
        listener: ProbeListener = ProbeListener { _, _, _ -> }
    ): ProbeRun {
        val start = clock.nowMs()
        val previous = if (resume) withContext(ioDispatcher) { store.loadFor(device.identity) } else null
        val results = LinkedHashMap<String, MutableList<ProbeResult>>()
        device.cameras.forEach { cam -> results[cam.id] = previous?.camera(cam.id)?.results?.filter { it.isDefinitive }.orEmpty().toMutableList() }
        val plan = planner.plan(device, cameraIds) { camera -> if (vendor.appliesTo(device.identity, camera)) vendor.variants(camera) else emptyList() }
        // The last run died inside this probe: record it as crashed so it is never retried automatically.
        previous?.inProgress?.let { crashed ->
            results[crashed.cameraId]?.let { list ->
                val advertised = plan.firstOrNull { it.cameraId == crashed.cameraId && it.config == crashed.config }?.advertised ?: false
                list.removeAll { it.config == crashed.config }
                list += ProbeResult(crashed.cameraId, crashed.config, advertised, ProbeOutcome.CRASHED, reason = "app stopped during this probe")
            }
        }
        var stop: StopReason? = null
        var cameraErrors = 0
        var sessions = 0

        fun snapshot(complete: Boolean, inProgress: InProgressProbe? = null) = DeviceCapabilities(
            identity = device.identity,
            engineVersion = ENGINE_VERSION,
            cameras = device.cameras.map { CameraCapabilities(it, results[it.id].orEmpty().toList()) },
            encoders = device.encoders,
            complete = complete,
            inProgress = inProgress,
            updatedAtEpochMs = wallClock()
        )

        suspend fun save(complete: Boolean = false, inProgress: InProgressProbe? = null) =
            snapshot(complete, inProgress).also { withContext(ioDispatcher) { store.save(it) } }

        try {
            plan.forEachIndexed { index, probe ->
                val known = results.getValue(probe.cameraId)
                if (known.any { it.config == probe.config && it.isDefinitive }) return@forEachIndexed
                if (probe.preset == null && stop == StopReason.CAMERA_ERRORS) return@forEachIndexed
                var opened = false
                val result = probe.preset?.let { base(probe, it, probe.reason) }
                    ?: stop?.let { base(probe, skipOutcome(it), "run stopped: $it") }
                    ?: Implications.decide(probe.config, known)?.let { (outcome, by) ->
                        base(probe, outcome, "${by.outcome} at ${by.config.label}").copy(impliedBy = by.config.label)
                    }
                    ?: if (clock.nowMs() - start + policy.probeCostMs > policy.budgetMs) {
                        stop = StopReason.BUDGET
                        base(probe, ProbeOutcome.SKIPPED_BUDGET, "budget of ${policy.budgetMs} ms spent")
                    } else if (!awaitCool(start)) {
                        stop = StopReason.THERMAL
                        base(probe, ProbeOutcome.SKIPPED_THERMAL, "thermal status ${thermal.status()} above ${policy.maxStartThermalStatus}")
                    } else {
                        opened = true
                        save(inProgress = InProgressProbe(probe.cameraId, probe.config))
                        val measured = measure(device, probe)
                        sessions += measured.sessions
                        classify(probe, measured.measurement, measured.vendorId).also { r ->
                            val failed = r.outcome == ProbeOutcome.CAMERA_ERROR || r.outcome == ProbeOutcome.TIMEOUT
                            cameraErrors = if (failed) cameraErrors + 1 else 0
                            if (cameraErrors >= policy.maxCameraErrors) stop = StopReason.CAMERA_ERRORS
                            if (measured.measurement.thermalStatusMax >= policy.abortThermalStatus) stop = StopReason.THERMAL
                        }
                    }
                known += result
                listener.onResult(index + 1, plan.size, result)
                if (opened) {
                    save()
                    if (stop == null && index < plan.lastIndex) delay(policy.coolDownMs)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + ioDispatcher) { store.save(snapshot(false)) }
            throw e
        }
        val complete = stop == null && plan.all { p -> results.getValue(p.cameraId).any { it.config == p.config && it.isDefinitive } }
        return ProbeRun(save(complete), stop, sessions, clock.nowMs() - start)
    }

    private fun skipOutcome(stop: StopReason) = if (stop == StopReason.BUDGET) ProbeOutcome.SKIPPED_BUDGET else ProbeOutcome.SKIPPED_THERMAL

    /** Waits until the phone is cool enough to start a probe; false if it is not within the wait or the budget. */
    private suspend fun awaitCool(start: Long): Boolean {
        val waitStart = clock.nowMs()
        while (thermal.status() > policy.maxStartThermalStatus) {
            val now = clock.nowMs()
            if (now - waitStart >= policy.thermalWaitMs || now - start + policy.thermalPollMs + policy.probeCostMs > policy.budgetMs) return false
            delay(policy.thermalPollMs)
        }
        return true
    }

    private data class Measured(val measurement: ProbeMeasurement, val vendorId: String?, val sessions: Int)

    /**
     * Runs the probe, with the vendor's session parameters if any; a refused or failed vendor session is retried plain.
     * A vendor variant ([VideoConfig.vendorMode]) is never retried plain: without its parameters it is not the variant.
     */
    private suspend fun measure(device: AdvertisedDevice, probe: PlannedProbe): Measured {
        val camera = device.camera(probe.cameraId)?.takeIf { vendor.appliesTo(device.identity, it) }
        val params = camera?.let { vendor.sessionParameters(it, probe.config) }.orEmpty()
        val sessionType = camera?.let { vendor.sessionType(it, probe.config) }
        val request = ProbeRequest(probe.cameraId, probe.config, policy.warmupMs, policy.measureMs, params, sessionType)
        if (probe.config.vendorMode != null) {
            if (params.isEmpty() && sessionType == null) {
                return Measured(ProbeMeasurement(SessionStatus.SESSION_REJECTED, error = "no vendor parameters for ${probe.config.vendorMode}"), null, 0)
            }
            return Measured(guarded(request), vendor.id, 1)
        }
        val first = guarded(request)
        if (params.isEmpty()) return Measured(first, null, 1)
        if (first.status != SessionStatus.SESSION_REJECTED && first.status != SessionStatus.CAMERA_ERROR) return Measured(first, vendor.id, 1)
        delay(policy.coolDownMs)
        return Measured(guarded(request.copy(vendorParameters = emptyList())), null, 2)
    }

    /** The runner under the watchdog: a probe that does not finish in time is cancelled (the runner releases the camera). */
    private suspend fun guarded(request: ProbeRequest): ProbeMeasurement {
        val limit = request.warmupMs + request.measureMs + policy.watchdogSlackMs
        return withTimeoutOrNull(limit) { runner.run(request) } ?: ProbeMeasurement(SessionStatus.TIMEOUT, error = "no answer within $limit ms")
    }

    private fun classify(probe: PlannedProbe, m: ProbeMeasurement, vendorId: String?): ProbeResult {
        val outcome = when (m.status) {
            SessionStatus.SESSION_REJECTED -> ProbeOutcome.SESSION_REJECTED
            SessionStatus.CAMERA_ERROR -> ProbeOutcome.CAMERA_ERROR
            SessionStatus.ENCODER_UNSUPPORTED -> ProbeOutcome.ENCODER_UNSUPPORTED
            SessionStatus.TIMEOUT -> ProbeOutcome.TIMEOUT
            SessionStatus.RAN -> when {
                m.droppedFrames > 0 -> ProbeOutcome.DROPS
                m.measuredFps < probe.config.fps * policy.fpsTolerance -> ProbeOutcome.LOW_FPS
                else -> ProbeOutcome.ACCEPTED_AND_HELD
            }
        }
        val ran = m.status == SessionStatus.RAN
        return base(probe, outcome, m.error).copy(
            measuredFps = m.measuredFps.takeIf { ran },
            droppedFrames = m.droppedFrames.takeIf { ran },
            thermalStatusMax = m.thermalStatusMax,
            thermalLimited = ran && outcome != ProbeOutcome.ACCEPTED_AND_HELD && m.thermalStatusMax >= policy.thermalLimitedStatus,
            vendorExtension = vendorId
        )
    }

    private fun base(probe: PlannedProbe, outcome: ProbeOutcome, reason: String?) = ProbeResult(
        cameraId = probe.cameraId,
        config = probe.config,
        advertised = probe.advertised,
        outcome = outcome,
        reason = reason,
        probedAtEpochMs = wallClock()
    )

    companion object {
        /** Bump when the plan or the "held" criterion changes: stored results from an older engine are re-probed. */
        const val ENGINE_VERSION = 1
    }
}
