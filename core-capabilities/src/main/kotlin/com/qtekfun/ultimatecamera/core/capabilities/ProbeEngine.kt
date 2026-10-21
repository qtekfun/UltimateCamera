package com.qtekfun.ultimatecamera.core.capabilities

import java.util.Locale
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
 * - Drops at the full rate (D83, D103): a run losing a few frames in short gaps is measured again over
 *   [ProbePolicy.sustainedMeasureMs], budget and temperature allowing. Clean, it holds; losing at most
 *   [ProbePolicy.tolerableDropShare] with no gap over [ProbePolicy.tolerableGapMs], it holds with
 *   [ProbeResult.marginal] ("a frame now and then", as some camera HALs do in their own apps); otherwise it is
 *   [ProbeOutcome.DROPS]. Without the retry, a loss of at most [ProbePolicy.marginalDropLimit] frames is recorded as
 *   marginal drops, which imply nothing about heavier configurations.
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
    /** Also probe the experimental direct 8K variants ([DirectMode], D71); only with Settings › Experimental on. */
    private val direct: Boolean = false,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val planner = ProbePlanner(policy)

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One loop with the stop rules side by side reads best.
    suspend fun run(
        device: AdvertisedDevice,
        cameraIds: List<String> = ProbePlanner.primaryCameraIds(device),
        resume: Boolean = true,
        listener: ProbeListener = ProbeListener { _, _, _ -> },
        /**
         * Probe again what hung or crashed before ([ProbeOutcome.TIMEOUT], [ProbeOutcome.CRASHED]) and what they implied
         * (D100): only for a run the user asked for, who is told to restart the phone if the camera stops answering.
         */
        retryHangs: Boolean = false
    ): ProbeRun {
        val start = clock.nowMs()
        val previous = if (resume) withContext(ioDispatcher) { store.loadFor(device.identity) } else null
        val results = LinkedHashMap<String, MutableList<ProbeResult>>()
        device.cameras.forEach { cam -> results[cam.id] = kept(previous?.camera(cam.id)?.results.orEmpty(), retryHangs).toMutableList() }
        val plan = planner.plan(device, cameraIds, direct, vendor::plans) { camera ->
            if (vendor.appliesTo(device.identity, camera)) vendor.variants(camera) else emptyList()
        }
        // The last run died inside this probe: record it as crashed so it is never retried automatically.
        previous?.inProgress?.takeUnless { retryHangs }?.let { crashed ->
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
                        val verdict = probeConfirmed(device, probe, start)
                        sessions += verdict.sessions
                        verdict.result.also { r ->
                            val failed = r.outcome == ProbeOutcome.CAMERA_ERROR || r.outcome == ProbeOutcome.TIMEOUT
                            cameraErrors = if (failed) cameraErrors + 1 else 0
                            if (cameraErrors >= policy.maxCameraErrors) stop = StopReason.CAMERA_ERRORS
                            if (verdict.thermalMax >= policy.abortThermalStatus) stop = StopReason.THERMAL
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

    private data class Verdict(val result: ProbeResult, val thermalMax: Int, val sessions: Int)

    /**
     * Runs [probe] and classifies it; a run losing a few frames in short gaps at the full rate is measured again over
     * the sustained window (D83, D103), as a few seconds cannot tell a rate of occasional gaps from chance. The sustained
     * verdict stands, with the first run noted in its reason; without it (budget, temperature) the first run stands.
     */
    private suspend fun probeConfirmed(device: AdvertisedDevice, probe: PlannedProbe, start: Long): Verdict {
        val measured = measure(device, probe)
        val first = classify(probe, measured.measurement, measured.vendorId)
        val thermalMax = measured.measurement.thermalStatusMax
        if (!sustainCandidate(probe, first) || thermalMax >= policy.abortThermalStatus) return Verdict(first, thermalMax, measured.sessions)
        val skip = retrySkipped(start)
        if (skip != null) return Verdict(first.copy(reason = "${firstRun(first)}; retry skipped: $skip"), thermalMax, measured.sessions)
        val again = measure(device, probe, policy.sustainedMeasureMs)
        val retry = tolerated(probe, classify(probe, again.measurement, again.vendorId))
        return Verdict(
            retry.copy(reason = retryReason(first, retry)),
            maxOf(thermalMax, again.measurement.thermalStatusMax),
            measured.sessions + again.sessions
        )
    }

    /**
     * Drops at the full rate, in short gaps and no more than [ProbePolicy.sustainCandidateDropShare] of the window: worth
     * the sustained measurement. A warm phone's drops say nothing, and a clear failure is not measured again.
     */
    private fun sustainCandidate(probe: PlannedProbe, r: ProbeResult): Boolean {
        if (r.outcome != ProbeOutcome.DROPS || r.thermalLimited) return false
        if ((r.measuredFps ?: 0.0) < probe.config.fps * policy.fpsTolerance) return false
        if ((r.maxGapMs ?: 0.0) > policy.tolerableGapMs) return false
        return (r.droppedFrames ?: 0) <= policy.expectedFrames(probe.config.fps, policy.measureMs) * policy.sustainCandidateDropShare
    }

    /**
     * The sustained run's verdict: drops within [ProbePolicy.tolerableDropShare] of the window, none in a gap over
     * [ProbePolicy.tolerableGapMs], at the full rate, hold "with a frame now and then" ([ProbeResult.marginal]); more is
     * [ProbeOutcome.DROPS], a load verdict that implies the heavier configurations fail.
     */
    private fun tolerated(probe: PlannedProbe, r: ProbeResult): ProbeResult {
        if (r.outcome != ProbeOutcome.DROPS || r.thermalLimited) return r
        val dropped = r.droppedFrames ?: return r
        val fullRate = (r.measuredFps ?: 0.0) >= probe.config.fps * policy.fpsTolerance
        val shortGaps = (r.maxGapMs ?: 0.0) <= policy.tolerableGapMs
        val few = dropped <= policy.expectedFrames(probe.config.fps, policy.sustainedMeasureMs) * policy.tolerableDropShare
        return if (fullRate && shortGaps && few) r.copy(outcome = ProbeOutcome.ACCEPTED_AND_HELD, marginal = true) else r.copy(marginal = false)
    }

    /** The first run in a reason: "marginal (…)" when it lost at most [ProbePolicy.marginalDropLimit] frames. */
    private fun firstRun(first: ProbeResult): String = "${if (first.marginal) "marginal" else "drops"} (${runSummary(first)})"

    /**
     * Waits the cool-down before the sustained run and says why it cannot be run, or null when it can: it must fit the
     * budget and start on a phone cool enough ([ProbePolicy.maxStartThermalStatus]); no thermal wait.
     */
    private suspend fun retrySkipped(start: Long): String? {
        if (clock.nowMs() - start + policy.coolDownMs + policy.sustainedProbeCostMs > policy.budgetMs) return "budget"
        delay(policy.coolDownMs)
        val status = thermal.status()
        return if (status > policy.maxStartThermalStatus) "thermal status $status" else null
    }

    /** The retry's verdict, with the first run in the reason so the diagnostics show both. */
    private fun retryReason(first: ProbeResult, retry: ProbeResult): String {
        val seconds = policy.sustainedMeasureMs / MS_PER_SECOND
        val verdict = when {
            retry.outcome == ProbeOutcome.ACCEPTED_AND_HELD && retry.marginal -> "held with occasional drops over $seconds s (${runSummary(retry)})"
            retry.outcome == ProbeOutcome.ACCEPTED_AND_HELD -> "held over $seconds s"
            else -> "over $seconds s: ${retry.outcome} (${runSummary(retry)})"
        }
        return listOfNotNull("$verdict; first run ${firstRun(first)}", retry.reason).joinToString("; ")
    }

    private fun runSummary(r: ProbeResult): String = buildString {
        append(String.format(Locale.ROOT, "%.1f fps, %d dropped", r.measuredFps ?: 0.0, r.droppedFrames ?: 0))
        r.gapCount?.let { append(", $it gaps") }
        r.maxGapMs?.let { append(String.format(Locale.ROOT, ", largest %.1f ms", it)) }
        r.cameraSkips?.takeIf { it > 0 }?.let { append(", $it skipped by the camera") }
        r.buffersLost?.takeIf { it > 0 }?.let { append(", $it buffers lost") }
    }

    /** The stored [results] a run keeps: the definitive ones, without the hangs and what they implied when [retryHangs]. */
    private fun kept(results: List<ProbeResult>, retryHangs: Boolean): List<ProbeResult> {
        val definitive = results.filter { it.isDefinitive }
        if (!retryHangs) return definitive
        val hung = definitive.filter { it.outcome in HANGS }.map { it.config.label }.toSet()
        return definitive.filter { it.config.label !in hung && it.impliedBy !in hung }
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
    private suspend fun measure(device: AdvertisedDevice, probe: PlannedProbe, measureMs: Long = policy.measureMs): Measured {
        // A direct variant (D71) is its own pipeline, never a vendor session.
        if (DirectMode.isDirect(probe.config)) return Measured(guarded(ProbeRequest(probe.cameraId, probe.config, policy.warmupMs, measureMs)), null, 1)
        val camera = device.camera(probe.cameraId)?.takeIf { vendor.appliesTo(device.identity, it) }
        val params = camera?.let { vendor.sessionParameters(it, probe.config) }.orEmpty()
        val sessionType = camera?.let { vendor.sessionType(it, probe.config) }
        val useCase = camera?.let { vendor.recordUseCase(it, probe.config) } ?: false
        val vendorId = camera?.let { vendor.idFor(it, probe.config) } ?: vendor.id
        val request = ProbeRequest(probe.cameraId, probe.config, policy.warmupMs, measureMs, params, sessionType, useCase)
        if (probe.config.vendorMode != null) {
            return if (params.isEmpty() && sessionType == null && !useCase) {
                Measured(ProbeMeasurement(SessionStatus.SESSION_REJECTED, error = "no vendor parameters for ${probe.config.vendorMode}"), null, 0)
            } else {
                Measured(guarded(request), vendorId, 1)
            }
        }
        val first = guarded(request)
        if (params.isEmpty()) return Measured(first, null, 1)
        if (first.status != SessionStatus.SESSION_REJECTED && first.status != SessionStatus.CAMERA_ERROR) return Measured(first, vendorId, 1)
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
                // Checked first: the frames the camera never produced also show up as gaps ("drops") downstream.
                m.cameraFps != null && m.cameraFps < probe.config.fps * policy.fpsTolerance -> ProbeOutcome.CAMERA_RATE_LIMITED
                m.droppedFrames > 0 -> ProbeOutcome.DROPS
                m.measuredFps < probe.config.fps * policy.fpsTolerance -> ProbeOutcome.LOW_FPS
                else -> ProbeOutcome.ACCEPTED_AND_HELD
            }
        }
        val ran = m.status == SessionStatus.RAN
        // A frame or so lost at the full rate (D83): measured again, and never a load verdict for heavier modes.
        val marginal = outcome == ProbeOutcome.DROPS &&
            m.droppedFrames <= policy.marginalDropLimit(probe.config.fps) &&
            m.measuredFps >= probe.config.fps * policy.fpsTolerance
        return base(probe, outcome, m.error).copy(
            measuredFps = m.measuredFps.takeIf { ran },
            droppedFrames = m.droppedFrames.takeIf { ran },
            thermalStatusMax = m.thermalStatusMax,
            thermalLimited = ran && outcome != ProbeOutcome.ACCEPTED_AND_HELD && m.thermalStatusMax >= policy.thermalLimitedStatus,
            vendorExtension = vendorId,
            marginal = marginal,
            gapCount = m.gapCount.takeIf { ran },
            maxGapMs = m.maxGapMs.takeIf { ran },
            cameraSkips = m.cameraSkips.takeIf { ran },
            buffersLost = m.buffersLost.takeIf { ran }
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
        /**
         * Bump when the plan or the "held" criterion changes: stored results from an older engine are re-probed.
         * 2: marginal drops are measured again and imply nothing (D83).
         * 3: frames the camera itself skips (low light) are not drops (D98).
         * 4: drops at the full rate are measured over a longer window and a frame now and then holds (D103).
         * 5: the front camera is measured too (D107).
         */
        const val ENGINE_VERSION = 5

        private const val MS_PER_SECOND = 1_000L

        /** Outcomes where the camera or the app stopped answering: kept across automatic runs, retried on request. */
        private val HANGS = setOf(ProbeOutcome.TIMEOUT, ProbeOutcome.CRASHED)
    }
}
