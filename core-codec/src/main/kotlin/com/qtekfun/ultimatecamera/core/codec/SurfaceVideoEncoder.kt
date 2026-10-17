package com.qtekfun.ultimatecamera.core.codec

import android.media.MediaCodec
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
class SurfaceVideoEncoder(
    val config: EncoderConfig,
    codecName: String,
    private val writer: Mp4Writer,
    private val ownsWriter: Boolean,
    /**
     * A persistent input surface ([primePersistentSurface]) to encode from instead of a new one: direct mode (D71), where
     * the camera session outputs into it for its whole life and each take attaches a new encoder. Not released by [stop].
     */
    private val persistentSurface: Surface? = null
) {
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
        codec.setCallback(Callback(), Handler(thread.looper))
        codec.configure(format(config), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = persistentSurface?.also(codec::setInputSurface) ?: codec.createInputSurface()
        codec.start()
    }

    /** Ends the stream and waits (up to [timeoutMs]) for the last frame to be written. Releases everything. */
    fun stop(timeoutMs: Long = 5_000) {
        runCatching { codec.signalEndOfInputStream() }.onFailure { error = error ?: "signalEndOfInputStream: ${it.message}" }
        if (!eos.await(timeoutMs, TimeUnit.MILLISECONDS)) error = error ?: "encoder did not reach end of stream in $timeoutMs ms"
        runCatching { codec.stop() }
        codec.release()
        if (persistentSurface == null) inputSurface.release()
        if (ownsWriter) {
            writer.stop()
            writer.error?.let { error = error ?: it }
        }
        thread.quitSafely()
    }

    companion object {
        /**
         * A persistent input surface sized and formatted for [config] (direct mode, D71): a codec is configured on it and
         * released at once, so the camera can configure its output before any take exists. Release it with the session.
         */
        fun primePersistentSurface(config: EncoderConfig, codecName: String): Surface {
            val surface = MediaCodec.createPersistentInputSurface()
            val primer = MediaCodec.createByCodecName(codecName)
            try {
                primer.configure(format(config), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                primer.setInputSurface(surface)
            } catch (e: IllegalArgumentException) {
                surface.release()
                throw e
            } catch (e: IllegalStateException) {
                surface.release()
                throw e
            } finally {
                primer.release()
            }
            return surface
        }

        private fun format(config: EncoderConfig): MediaFormat = MediaFormat.createVideoFormat(config.codec.mime, config.width, config.height).apply {
            encoderFormatKeys(config).forEach { (key, value) -> setInteger(key, value) }
        }
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
