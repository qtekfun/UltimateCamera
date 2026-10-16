package com.qtekfun.ultimatecamera.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class AudioInput(
    val id: Int,
    val type: String,
    val kind: AudioInputKind,
    val productName: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<Int>
)

@Serializable
data class AudioRecordingResult(
    val deviceId: Int,
    val type: String,
    /** "preferred" (AudioRecord.setPreferredDevice) or "communication" (AudioManager.setCommunicationDevice, SCO/LE). */
    val route: String,
    val requestedSampleRate: Int,
    val requestedChannels: Int,
    val actualSampleRate: Int = 0,
    val actualChannels: Int = 0,
    val routedDeviceId: Int? = null,
    val routedType: String? = null,
    /** True when the system really recorded from the device that was asked for. */
    val preferredHonored: Boolean = false,
    val peakDbfs: Double? = null,
    val rmsDbfs: Double? = null,
    val framesRead: Long = 0,
    val expectedFrames: Long = 0,
    val wavFile: String? = null,
    val error: String? = null
)

/** Phase 0 audio checks: list the inputs and record a few seconds from each with the routing the app will use. */
class AudioInputProbe(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun devices(): List<AudioDeviceInfo> = preferredOrder(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()) { it.type }

    fun inputs(): List<AudioInput> = devices().map { d ->
        AudioInput(
            id = d.id,
            type = AudioInputKind.typeName(d.type),
            kind = AudioInputKind.of(d.type),
            productName = d.productName?.toString().orEmpty(),
            sampleRates = d.sampleRates.toList(),
            channelCounts = d.channelCounts.toList(),
            encodings = d.encodings.toList()
        )
    }

    /** Records [seconds] from [device] into a WAV in [dir]. Needs RECORD_AUDIO (checked by the caller). */
    @Suppress("TooGenericExceptionCaught") // Probe boundary: any vendor audio failure is a result, not a crash.
    suspend fun record(device: AudioDeviceInfo, seconds: Int, dir: File): AudioRecordingResult = withContext(Dispatchers.IO) {
        val communication = AudioInputKind.needsCommunicationRoute(device.type)
        val channels = if (device.channelCounts.isEmpty() || 2 in device.channelCounts) 2 else 1
        val base = AudioRecordingResult(
            deviceId = device.id,
            type = AudioInputKind.typeName(device.type),
            route = if (communication) "communication" else "preferred",
            requestedSampleRate = SAMPLE_RATE,
            requestedChannels = channels,
            expectedFrames = SAMPLE_RATE.toLong() * seconds
        )
        val previousMode = audioManager.mode
        try {
            if (communication) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (!audioManager.setCommunicationDevice(device)) return@withContext base.copy(error = "setCommunicationDevice refused the device")
            }
            val file = File(dir, "audio-${device.id}-${AudioInputKind.typeName(device.type)}.wav")
            capture(device, communication, channels, seconds, file, base)
        } catch (e: Exception) {
            base.copy(error = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (communication) {
                audioManager.clearCommunicationDevice()
                audioManager.mode = previousMode
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun capture(
        device: AudioDeviceInfo,
        communication: Boolean,
        channels: Int,
        seconds: Int,
        file: File,
        base: AudioRecordingResult
    ): AudioRecordingResult {
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return base.copy(error = "unsupported format (getMinBufferSize=$minBuffer)")
        val recorder = AudioRecord.Builder()
            .setAudioSource(if (communication) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(channelMask).build())
            .setBufferSizeInBytes(minBuffer * 4)
            .build()
        try {
            if (!communication) recorder.setPreferredDevice(device)
            recorder.startRecording()
            val meter = LevelMeter()
            val (samples, routed) = streamToWav(recorder, file, seconds, ShortArray(minBuffer), meter)
            return base.copy(
                actualSampleRate = recorder.sampleRate,
                actualChannels = recorder.channelCount,
                routedDeviceId = routed?.id,
                routedType = routed?.let { AudioInputKind.typeName(it.type) },
                preferredHonored = routed?.id == device.id,
                peakDbfs = meter.peakDbfs.takeIf { it.isFinite() },
                rmsDbfs = meter.rmsDbfs.takeIf { it.isFinite() },
                framesRead = samples / recorder.channelCount,
                wavFile = file.absolutePath
            )
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /**
     * Reads for [seconds], streaming to disk as it goes (only [buffer] is held in memory). Returns the samples read and the
     * device the system actually routed to, sampled once routing has settled after the first reads.
     */
    private fun streamToWav(recorder: AudioRecord, file: File, seconds: Int, buffer: ShortArray, meter: LevelMeter): Pair<Long, AudioDeviceInfo?> {
        var samples = 0L
        var routed: AudioDeviceInfo? = null
        val end = SystemClock.elapsedRealtime() + seconds * 1_000L
        WavWriter(file, recorder.sampleRate, recorder.channelCount).use { wav ->
            while (SystemClock.elapsedRealtime() < end) {
                val n = recorder.read(buffer, 0, buffer.size)
                check(n >= 0) { "AudioRecord.read returned $n" }
                wav.write(buffer, n)
                meter.add(buffer, n)
                samples += n
                if (routed == null && samples > SAMPLE_RATE / 5) routed = recorder.routedDevice
            }
        }
        return samples to routed
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
