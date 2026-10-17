package com.qtekfun.ultimatecamera.core.recording

import android.util.Log
import com.qtekfun.ultimatecamera.core.codec.VideoContainer
import com.qtekfun.ultimatecamera.core.codec.mp4.DescriptorSeekableFile
import com.qtekfun.ultimatecamera.core.codec.mp4.Mp4PostProcessor
import com.qtekfun.ultimatecamera.core.codec.mp4.PostProcessOptions
import com.qtekfun.ultimatecamera.core.codec.mp4.PostProcessResult
import com.qtekfun.ultimatecamera.core.codec.mp4.TimecodeStart
import java.time.ZoneId

/**
 * The post-process of a finished take part (D69), run on its closed MediaMuxer file through the still-open read-write
 * descriptor before it is synced and closed: a QuickTime timecode track with the part's time-of-day start (same count
 * as the screen and the BWF, D58), the creation date, and the QuickTime conversion for `.mov` takes. Anything
 * unexpected or failing is logged and leaves MediaMuxer's file as it was.
 */
internal object TakeFinishing {
    private const val TAG = "UcTakeFinishing"
    private const val NANOS_PER_S = 1_000_000_000L

    /** What to add to a part that started at [startEpochNanos] (null = unknown: no timecode, no date) at [fps]. */
    fun options(
        startEpochNanos: Long?,
        fps: Int,
        timecodeTrack: Boolean,
        container: VideoContainer,
        zone: ZoneId = ZoneId.systemDefault()
    ): PostProcessOptions {
        val start = startEpochNanos?.takeIf { timecodeTrack && fps > 0 }
        return PostProcessOptions(
            timecode = start?.let { TimecodeStart.ofTimeOfDay(it, TakeTimecode.utcOffsetSeconds(it, zone), fps) },
            creationEpochSeconds = start?.let { Math.floorDiv(it, NANOS_PER_S) },
            quickTime = container == VideoContainer.MOV
        )
    }

    // A failure here must never cost the take: whatever the platform throws is logged, the file stays MediaMuxer's.
    @Suppress("TooGenericExceptionCaught")
    fun postProcess(output: VideoOutput, startEpochNanos: Long?, fps: Int, timecodeTrack: Boolean): PostProcessResult {
        val opts = options(startEpochNanos, fps, timecodeTrack, output.container)
        if (opts.isEmpty) return PostProcessResult.Skipped("off")
        val result = try {
            Mp4PostProcessor.process(DescriptorSeekableFile(output.fileDescriptor), opts)
        } catch (e: RuntimeException) {
            PostProcessResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
        if (result is PostProcessResult.Done) {
            Log.i(TAG, "${output.displayName}: ${opts.timecode?.label ?: "no timecode"}, +${result.addedBytes} bytes")
        } else {
            Log.w(TAG, "${output.displayName}: post-process not applied: $result")
        }
        return result
    }
}
