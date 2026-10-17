package com.qtekfun.ultimatecamera.core.codec

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A MediaCodec encoder fed through its input [Surface] (the GPU renders into it), writing one track of [writer].
 *
 * Output buffers are drained on the encoder's own thread in asynchronous mode, so neither the render thread nor the
 * camera thread ever waits for the codec. Counters are readable from any thread while it runs. When [ownsWriter] the
 * encoder also finishes the file on [stop] (single-track files, as in the spike).
 */
class SurfaceVideoEncoder(val config: EncoderConfig, codecName: String, private val writer: Mp4Writer, private val ownsWriter: Boolean) {
    /** A video-only MP4 at [output]. */
    constructor(config: EncoderConfig, codecName: String, output: File) : this(config, codecName, Mp4Writer(output, expectedTracks = 1), ownsWriter = true)

    private val thread = HandlerThread("uc-encoder").apply { start() }
    private val codec = MediaCodec.createByCodecName(codecName)
    private var track: Mp4Writer.Track? = null
    private val eos = CountDownLatch(1)

    val inputSurface: Surface
    val encodedFrames = AtomicInteger()
    val encodedBytes = AtomicLong()
    val lastPtsUs = AtomicLong(-1)
    val firstPtsUs = AtomicLong(-1)

    @Volatile var error: String? = null
        private set

    /** The format the encoder settled on (null until the first output), for the report. */
    @Volatile var outputFormat: String? = null
        private set

    init {
        val format = MediaFormat.createVideoFormat(config.codec.mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)
            // Realtime priority: the codec must keep up with the camera, not batch for throughput.
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // Without the real rate several vendors keep the encoder clock low and drop frames at 4K60.
            setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps)
            if (config.tenBit) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    if (config.codec == VideoCodec.AV1) CodecProfileLevel.AV1ProfileMain10 else CodecProfileLevel.HEVCProfileMain10
                )
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }
        codec.setCallback(Callback(), Handler(thread.looper))
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    /** Ends the stream and waits (up to [timeoutMs]) for the last frame to be written. Releases everything. */
    fun stop(timeoutMs: Long = 5_000) {
        runCatching { codec.signalEndOfInputStream() }.onFailure { error = error ?: "signalEndOfInputStream: ${it.message}" }
        if (!eos.await(timeoutMs, TimeUnit.MILLISECONDS)) error = error ?: "encoder did not reach end of stream in $timeoutMs ms"
        runCatching { codec.stop() }
        codec.release()
        inputSurface.release()
        if (ownsWriter) {
            writer.stop()
            writer.error?.let { error = error ?: it }
        }
        thread.quitSafely()
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // Surface input: never called.

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format.toString()
            track = runCatching { writer.addTrack(format) }.onFailure { error = error ?: "addTrack: ${it.message}" }.getOrNull()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val t = track
            if (!isConfig && info.size > 0 && t != null) {
                codec.getOutputBuffer(index)?.let { t.write(it, info) }
                encodedFrames.incrementAndGet()
                encodedBytes.addAndGet(info.size.toLong())
                firstPtsUs.compareAndSet(-1, info.presentationTimeUs)
                lastPtsUs.set(info.presentationTimeUs)
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.countDown()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            error = "codec error ${e.diagnosticInfo}: ${e.message}"
            eos.countDown()
        }
    }
}
