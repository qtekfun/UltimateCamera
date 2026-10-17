package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import com.qtekfun.ultimatecamera.core.audio.AudioInputChoice
import com.qtekfun.ultimatecamera.core.audio.AudioInputs
import com.qtekfun.ultimatecamera.core.recording.AudioConfig
import com.qtekfun.ultimatecamera.core.recording.StorageVideoOutput
import com.qtekfun.ultimatecamera.core.recording.WavSidecar
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import com.qtekfun.ultimatecamera.core.storage.ActiveRecordingStreams
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.RecordingStreams
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The audio part of a take, from Settings: input device (REQ-AUD-001), gain (REQ-AUD-004) and the separate WAV
 * (REQ-AUD-003). Kept out of [CameraViewModel] so the camera screen only needs small hooks.
 */
internal class TakeAudio(context: Context, private val settings: SettingsRepository) {
    private val inputs = AudioInputs(context)

    /**
     * The take's audio settings: the stored input if connected, else the best one (USB > BT > built-in), the stored
     * gain, and the WAV next to [video] when the separate WAV is on.
     */
    suspend fun config(backend: StorageBackend, location: RecordingLocation, video: StorageVideoOutput): AudioConfig {
        val prefs = settings.settings.first()
        val device = withContext(Dispatchers.Default) { AudioInputChoice.resolve(inputs.list(), prefs.audioInputKey)?.let(inputs::device) }
        val wav = if (prefs.separateWav) createWav(backend, location, video) else null
        return AudioConfig(gainDb = prefs.audioGainDb.toDouble(), device = device, wav = wav)
    }

    private suspend fun createWav(backend: StorageBackend, location: RecordingLocation, video: StorageVideoOutput): OutputFile? = withContext(Dispatchers.IO) {
        try {
            WavSidecar.create(backend, location, video.names)
        } catch (_: IOException) {
            null // The MP4 still carries the AAC track; a missing WAV must not cost the take.
        }
    }

    /** What the take writes at [videoBitsPerSecond], published for the Settings write-speed verdict. */
    suspend fun streams(videoBitsPerSecond: Long): RecordingStreams {
        val separateWav = settings.settings.first().separateWav
        return RecordingStreams(
            videoBitsPerSecond = videoBitsPerSecond,
            audioBitsPerSecond = AudioConfig().bitrateBps.toLong(),
            separateWavBitsPerSecond = if (separateWav) RecordingStreams.SEPARATE_WAV_BITS_PER_SECOND else 0
        ).also { ActiveRecordingStreams.current.value = it }
    }
}
