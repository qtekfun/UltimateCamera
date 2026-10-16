package com.qtekfun.ultimatecamera.feature.spike

import com.qtekfun.ultimatecamera.core.net.monitor.MonitorLinkResult
import com.qtekfun.ultimatecamera.feature.spike.pipeline.PipelineResult
import java.util.Locale

/** One-line, language-neutral summaries of a report (numbers and units), for the screen and the logs. */
object SpikeSummary {
    fun lines(report: SpikeReport): List<String> = buildList {
        addAll(hardware(report))
        report.pipeline.forEach { add(pipeline(it)) }
        addAll(concurrentAudioNetwork(report))
        report.monitorLinks.forEach { add(monitor(it)) }
        report.errors.forEach { add("! $it") }
    }

    private fun hardware(report: SpikeReport): List<String> = buildList {
        report.gpu?.let {
            add("GPU ${it.renderer} · 1010102=${it.rgba1010102Recordable} · HLG=${it.bt2020HlgColorspace} · YUV_target=${it.extensions["GL_EXT_YUV_target"]}")
        }
        report.cameras?.let { cams ->
            add("Cameras: ${cams.size} (${cams.count { "DYNAMIC_RANGE_TEN_BIT" in it.capabilities }} with 10-bit)")
            cams.forEach { c ->
                val focal = c.focalLengthsMm.joinToString("/")
                add("  #${c.id} ${c.facing} ${focal}mm ${c.dynamicRangeProfiles.joinToString(",")} vendorKeys=${c.vendorCharacteristicKeys.size}")
            }
        }
        report.encoders.orEmpty().filter { it.hardware }.forEach { e ->
            add("Encoder ${e.name} ${e.codec} 10bit=${e.tenBitProfile} 4K60=${e.supports["3840x2160@60"]}")
        }
    }

    fun pipeline(r: PipelineResult): String = when {
        !r.supported -> "· ${r.label}: unsupported (${r.reason})"
        r.error != null && r.gpu == null -> "✗ ${r.label}: ${r.error}"
        else -> String.format(
            Locale.ROOT,
            "%s %s: gpu %.1f fps, drops sensor/gpu %d/%d, enc %d/%d, render p95 %.1f ms, %.0f Mbps, thermal %d→%d%s",
            if (r.meetsTarget) "✓" else "✗",
            r.label,
            r.gpu?.fps ?: 0.0,
            r.sensor?.dropped ?: -1,
            r.gpu?.dropped ?: -1,
            r.encodedFramesTotal,
            r.gpuFramesTotal,
            r.renderCpu?.p95Ms ?: 0.0,
            r.encodedMbps,
            r.thermal.firstOrNull()?.status ?: -1,
            r.thermal.maxOfOrNull { it.status } ?: -1,
            r.error?.let { " · $it" }.orEmpty()
        )
    }

    private fun monitor(m: MonitorLinkResult): String {
        val g2g = m.latency?.glassToGlass
        val latency = g2g?.let { String.format(Locale.ROOT, "g2g p50 %.0f / p95 %.0f ms, %d/%d echoed", it.p50Ms, it.p95Ms, it.received, it.sent) }
        val receive = m.receive?.let { "stamps ${it.stampsDecoded}/${it.framesReceived}" }
        val via = m.candidatePair?.let { " via ${it.localNetworkType ?: "?"}" }.orEmpty()
        return "Monitor ${m.role} ${m.link?.name ?: "-"} ${m.peer.orEmpty()}: ${latency ?: receive ?: "no data"}$via ${m.error.orEmpty()}"
    }

    private fun concurrentAudioNetwork(report: SpikeReport): List<String> = buildList {
        report.concurrent?.let { c ->
            if (c.sets.isEmpty()) add("Concurrent cameras: none declared")
            c.sets.forEach { s ->
                val fps = s.measuredFps.mapValues { "%.1f".format(Locale.ROOT, it.value) }
                add("Concurrent ${s.cameraIds}: declared=${s.declaredSupported} fps=$fps ${s.error.orEmpty()}")
            }
        }
        report.audioRecordings.forEach { a ->
            val peak = a.peakDbfs?.let { "%.1f".format(Locale.ROOT, it) }
            val format = "${a.actualSampleRate}Hz×${a.actualChannels}"
            add("Audio #${a.deviceId} ${a.type} ${a.route}: routed=${a.routedType} honored=${a.preferredHonored} $format peak=$peak ${a.error.orEmpty()}")
        }
        report.network.forEach { n ->
            val s = n.stats
            add(
                String.format(
                    Locale.ROOT,
                    "Net %s %s: p50 %.1f / p95 %.1f / p99 %.1f ms, loss %.1f%% %s",
                    n.link?.name ?: "-",
                    n.target,
                    s.p50Ms,
                    s.p95Ms,
                    s.p99Ms,
                    s.lossPercent,
                    n.error.orEmpty()
                )
            )
        }
    }
}
