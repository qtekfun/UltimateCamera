package com.qtekfun.ultimatecamera.core.recording

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import com.qtekfun.ultimatecamera.core.audio.PcmSink
import com.qtekfun.ultimatecamera.core.audio.dsp.AudioChain
import com.qtekfun.ultimatecamera.core.audio.dsp.ChannelMap
import com.qtekfun.ultimatecamera.core.audio.dsp.MeterSnapshot
import com.qtekfun.ultimatecamera.core.audio.sync.AudioClockMapper
import com.qtekfun.ultimatecamera.core.audio.sync.TakeStart
import com.qtekfun.ultimatecamera.core.codec.Mp4Writer
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import java.io.IOException

/** Audio settings of a take: AAC-LC in the MP4 (REQ-AUD-003). */
data class AudioConfig(
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    val bitrateBps: Int = 192_000,
    val gainDb: Double = 0.0,
    /** The input to record from (USB > BT > internal is chosen by the caller, REQ-AUD-001); null = system default. */
    val device: AudioDeviceInfo? = null,
    /**
     * The take's separate WAV (REQ-AUD-003, [WavSidecar.create]), or null for none. The recorder fills it from the first
     * video frame and keeps or deletes it with the MP4.
     */
    val wav: OutputFile? = null
)

/**
 * Records one audio track: `AudioRecord → AudioChain (gain, limiter, meter) → AAC → Mp4Writer`, on its own
 * high-priority thread. Presentation times come from `AudioRecord.getTimestamp` in [timebase] (the camera's clock, so
 * audio and video share one timeline) through [AudioClockMapper], minus the limiter look-ahead. Samples before
 * [startNanos] are dropped, so the track starts with the video.
 *
 * [pcmTap] (the separate WAV, REQ-AUD-003) gets the same processed PCM as the encoder, from the sample at [startNanos]
 * on (sample-accurate, see [TakeStart]) and then every buffer without gaps. A failing tap is dropped and reported in
 * [tapError]; the MP4 goes on.
 *
 * Buffers are allocated once; the per-buffer path does not allocate (owner rule: modest phones).
 */
class AudioTrackRecorder(
    private val config: AudioConfig,
    private val writer: Mp4Writer,
    private val timebase: Int,
    private val startNanos: Long,
    private val pcmTap: PcmSink? = null,
    /** Also writes the encoded AAC into the take's proxy (D59). */
    private val mirror: AudioMirror? = null
) {
    private val channelMask = if (config.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private val frameBytes = 2 * config.channels
    private val framesPerRead = config.sampleRate / READS_PER_SECOND
    private val pcm = ShortArray(framesPerRead * config.channels)
    private val chain = AudioChain(config.sampleRate, ChannelMap.identity(config.channels), framesPerRead, config.gainDb)
    private val clock = AudioClockMapper(config.sampleRate)
    private val timestamp = AudioTimestamp()
    private val info = MediaCodec.BufferInfo()

    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var error: String? = null
        private set

    /** Why [pcmTap] stopped receiving audio (e.g. the drive of the WAV filled up), or null. */
    @Volatile var tapError: String? = null
        private set

    private var tapStarted = false

    /** The device the system really routed to (may differ from the requested one, REQ-AUD-002). */
    @Volatile var routedDevice: AudioDeviceInfo? = null
        private set

    /** Copies the latest meter values into [into]; safe from the UI thread. */
    fun readMeter(into: MeterSnapshot): Boolean = chain.meter.read(into)

    /** Gain in dB, changeable while recording (smoothed by the chain). */
    var gainDb: Double
        get() = chain.gain.gainDb
        set(value) {
            chain.gain.gainDb = value
        }

    @SuppressLint("MissingPermission") // The caller checks RECORD_AUDIO before recording.
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(config.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(config.sampleRate).setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, framesPerRead * frameBytes * 4))
            .build()
        config.device?.let(record::setPreferredDevice)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, framesPerRead * frameBytes)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        record.startRecording()
        running = true
        thread = Thread({ loop(record, codec) }, "uc-audio").apply { start() }
    }

    /** Stops capture, flushes the encoder and waits for the thread. The writer is finished by the caller. */
    fun stop() {
        running = false
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
    }

    @Suppress("TooGenericExceptionCaught") // Thread boundary: any platform failure ends the track, not the app.
    private fun loop(record: AudioRecord, codec: MediaCodec) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running && step(record, codec)) Unit
            queue(codec, 0, Long.MAX_VALUE, endOfStream = true)
            drain(codec, endOfStream = true)
        } catch (e: Exception) {
            error = error ?: "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private var track: Mp4Writer.Track? = null
    private var framesRead = 0L
    private var lastPtsUs = -1L

    /** Reads, processes and encodes one buffer; false when capture failed. */
    private fun step(record: AudioRecord, codec: MediaCodec): Boolean {
        val n = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
        if (n <= 0) {
            error = error ?: "AudioRecord.read returned $n"
            return false
        }
        if (routedDevice == null) routedDevice = record.routedDevice
        val frames = n / config.channels
        if (record.getTimestamp(timestamp, timebase) == AudioRecord.SUCCESS) clock.add(timestamp.framePosition, timestamp.nanoTime)
        chain.process(pcm, frames)
        // Output frame k is input frame k - latency (limiter look-ahead).
        val ptsNanos = if (clock.points > 0) clock.frameToNanos(framesRead - chain.latencyFrames) else -1L
        framesRead += frames
        if (ptsNanos >= 0) tap(ptsNanos, frames)
        // Before the video starts the meter still runs, but nothing is encoded.
        if (ptsNanos >= startNanos) queue(codec, frames, ptsNanos / NANOS_PER_MICRO, endOfStream = false)
        drain(codec, endOfStream = false)
        return true
    }

    /** Feeds [pcmTap]: trimmed to [startNanos] on the first buffer, then continuous. */
    private fun tap(ptsNanos: Long, frames: Int) {
        val sink = pcmTap ?: return
        if (tapError != null) return
        val skip = if (tapStarted) 0 else TakeStart.framesToSkip(ptsNanos, frames, startNanos, config.sampleRate) ?: return
        tapStarted = true
        try {
            sink.write(pcm, skip * config.channels, (frames - skip) * config.channels)
        } catch (e: IOException) {
            tapError = "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun queue(codec: MediaCodec, frames: Int, ptsUs: Long, endOfStream: Boolean) {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return // The encoder is behind: drop this buffer rather than block capture.
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        val samples = frames * config.channels
        buffer.asShortBuffer().put(pcm, 0, samples)
        // PTS must grow strictly; the clock fit can step back by a few µs when it re-anchors.
        val pts = if (endOfStream) maxOf(lastPtsUs + 1, 0) else maxOf(ptsUs, lastPtsUs + 1)
        lastPtsUs = pts
        codec.queueInputBuffer(index, 0, samples * 2, pts, if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
    }

    /** Writes every ready output buffer; at the end of the stream waits (bounded) for the last one. */
    private fun drain(codec: MediaCodec, endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) DRAIN_EOS_TIMEOUT_US else 0)
            val done = when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> !endOfStream || ++waits > MAX_EOS_WAITS
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = writer.addTrack(codec.outputFormat)
                    mirror?.onFormat(codec.outputFormat)
                    false
                }
                index >= 0 -> writeOutput(codec, index)
                else -> false
            }
            if (done) return
        }
    }

    /** Writes output buffer [index]; true when it was the end of the stream. */
    private fun writeOutput(codec: MediaCodec, index: Int): Boolean {
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (!isConfig && info.size > 0) {
            codec.getOutputBuffer(index)?.let {
                track?.write(it, info)
                mirror?.write(it, info)
            }
        }
        codec.releaseOutputBuffer(index, false)
        return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    private companion object {
        /** 20 ms reads: low latency for the meter, few codec calls. */
        const val READS_PER_SECOND = 50
        const val INPUT_TIMEOUT_US = 10_000L
        const val DRAIN_EOS_TIMEOUT_US = 100_000L
        const val MAX_EOS_WAITS = 20
        const val STOP_TIMEOUT_MS = 3_000L
        const val NANOS_PER_MICRO = 1_000L
    }
}
