package com.qtekfun.ultimatecamera.core.recording

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.CameraStream
import com.qtekfun.ultimatecamera.core.camera.SessionRejectedException
import com.qtekfun.ultimatecamera.core.camera.StreamSpec
import com.qtekfun.ultimatecamera.core.codec.CodecCapabilityProbe
import com.qtekfun.ultimatecamera.core.codec.EncoderConfig
import com.qtekfun.ultimatecamera.core.codec.EncoderInfo
import com.qtekfun.ultimatecamera.core.codec.Mp4Writer
import com.qtekfun.ultimatecamera.core.codec.SurfaceVideoEncoder
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A recording mode: what the camera streams and how it is encoded. */
data class RecordingProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: VideoCodec = VideoCodec.HEVC,
    val tenBit: Boolean = true,
    val bitrateBps: Int = defaultBitrate(width, height, fps, tenBit, codec),
    val stabilization: Boolean = false
) {
    val encoderConfig: EncoderConfig get() = EncoderConfig(codec, width, height, fps, bitrateBps, tenBit)

    val label: String get() = "${sizeLabel(height)}$fps${if (tenBit) " 10-bit" else ""}${if (codec == VideoCodec.HEVC) "" else " ${codec.name}"}"

    companion object {
        fun sizeLabel(height: Int): String = when {
            height >= 4320 -> "8K"
            height >= 2160 -> "4K"
            else -> "${height}p"
        }

        /** Bitrates measured to look clean in the spike (4K60 10-bit ≈ 100 Mbit/s), scaled by pixels per second. */
        fun defaultBitrate(width: Int, height: Int, fps: Int, tenBit: Boolean, codec: VideoCodec = VideoCodec.HEVC): Int {
            val pixelsPerSecond = width.toLong() * height * fps
            // H.264 needs about 1.5x HEVC's bitrate for the same quality; AV1 about 0.8x.
            val codecFactor = when (codec) {
                VideoCodec.H264 -> 1.5
                VideoCodec.HEVC -> 1.0
                VideoCodec.AV1 -> 0.8
            }
            val bitsPerPixel = (if (tenBit) 0.2 else 0.17) * codecFactor
            return (pixelsPerSecond * bitsPerPixel).toLong().coerceIn(MIN_BITRATE, MAX_BITRATE).toInt()
        }

        private const val MIN_BITRATE = 4_000_000L
        private const val MAX_BITRATE = 400_000_000L
    }
}

/** What the camera screen shows. */
sealed interface CameraState {
    data object Closed : CameraState

    data class Previewing(val profile: RecordingProfile) : CameraState

    data class Recording(val profile: RecordingProfile, val startedAtElapsedMs: Long, val fileName: String) : CameraState

    data class Failed(val message: String, val rejected: Boolean) : CameraState
}

/**
 * The camera of the main screen: one stream into the [CameraRenderer], preview always on, REC attaches an encoder
 * (and the audio track) without touching the camera session. Call from one coroutine scope (the view model).
 */
class CameraController(private val context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("uc-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val encoders: List<EncoderInfo> by lazy { CodecCapabilityProbe.encoders() }

    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private var stream: CameraStream? = null
    private var renderer: CameraRenderer? = null
    private var chars: CameraCharacteristics? = null
    private var preview: Surface? = null
    private var lut: CubeLut? = null
    private var previewRotation = 0
    private var monitor = MonitorSettings.OFF

    private var take: Take? = null

    /** Audio meter of the take in progress (null when not recording). */
    val audio: AudioTrackRecorder? get() = take?.audio

    /** The main back camera (the first back-facing logical camera). */
    fun mainBackCamera(): String? = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    /** Opens [profile] on [cameraId] and starts the preview. Closes whatever was open. */
    @Suppress("TooGenericExceptionCaught") // Vendor HALs throw anything; the screen shows it instead of crashing.
    suspend fun open(cameraId: String, profile: RecordingProfile) = withContext(Dispatchers.Default) {
        close()
        try {
            val c = manager.getCameraCharacteristics(cameraId).also { chars = it }
            val tenBitProfile = if (profile.tenBit) {
                CameraStream.tenBitProfile(c) ?: throw SessionRejectedException("no 10-bit profile on camera $cameraId")
            } else {
                null
            }
            // Checked here, not at REC: a mode the phone cannot encode must fall down the ladder before the user records.
            pickEncoder(profile) ?: throw SessionRejectedException("no ${profile.codec} encoder for ${profile.label}")
            val r = CameraRenderer(
                depth = if (profile.tenBit) EglColorDepth.RGBA1010102 else EglColorDepth.RGBA8888,
                width = profile.width,
                height = profile.height,
                hlg = profile.tenBit,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            ).also { renderer = it }
            r.setLut(lut)
            r.previewRotation = previewRotation
            r.setMonitorSettings(monitor)
            preview?.let(r::setPreview)
            val spec = StreamSpec(cameraId, profile.width, profile.height, profile.fps, tenBitProfile, profile.stabilization)
            stream = CameraStream.open(manager, spec, r.cameraSurface, cameraHandler) { reason -> _state.value = CameraState.Failed(reason, rejected = false) }
            _state.value = CameraState.Previewing(profile)
        } catch (e: SessionRejectedException) {
            release()
            _state.value = CameraState.Failed(e.message ?: "rejected", rejected = true)
        } catch (e: Exception) {
            release()
            _state.value = CameraState.Failed("${e.javaClass.simpleName}: ${e.message}", rejected = false)
        }
    }

    private fun pickEncoder(profile: RecordingProfile): EncoderInfo? = CodecCapabilityProbe.pick(encoders, profile.encoderConfig)

    /**
     * Rotation in degrees that makes the open camera's frames upright on a display rotated by [displayRotationDegrees]
     * (`Surface.ROTATION_*` × 90): the MP4 orientation hint, and 180 means the preview must be turned too.
     */
    fun uprightRotation(displayRotationDegrees: Int): Int = chars?.let { uprightRotation(it, displayRotationDegrees) } ?: 0

    /** The display's rotation (0, 90, 180, 270): the preview is turned by it (see [CameraRenderer.previewRotation]). */
    fun setPreviewRotation(degrees: Int) {
        previewRotation = degrees
        renderer?.previewRotation = degrees
    }

    /** The view's surface to draw the preview on (null when the view goes away). */
    fun setPreview(surface: Surface?) {
        preview = surface
        renderer?.setPreview(surface)
    }

    fun setLut(newLut: CubeLut?) {
        lut = newLut
        renderer?.setLut(newLut)
    }

    /** Monitoring tools of the preview (REQ-MON-001); kept across camera reopenings, never recorded. */
    fun setMonitorSettings(settings: MonitorSettings) {
        monitor = settings
        renderer?.setMonitorSettings(settings)
    }

    /** Starts a take into [output] with [audioConfig] (null = video only). */
    @Suppress("TooGenericExceptionCaught")
    suspend fun startRecording(output: VideoOutput, audioConfig: AudioConfig?, orientationHint: Int) = withContext(Dispatchers.Default) {
        val current = _state.value as? CameraState.Previewing ?: return@withContext WavSidecar.discard(audioConfig?.wav)
        val r = renderer ?: return@withContext
        val c = chars ?: return@withContext
        val profile = current.profile
        try {
            val writer = Mp4Writer(output.fileDescriptor, expectedTracks = if (audioConfig != null) 2 else 1, orientationHint = orientationHint)
            val info = pickEncoder(profile) ?: error("no ${profile.codec} encoder for ${profile.label}")
            val video = SurfaceVideoEncoder(profile.encoderConfig, info.name, writer, ownsWriter = false)
            val start = CameraStream.nowNanos(c)
            val sidecar = audioConfig?.wav?.let { WavSidecar(it, audioConfig) }
            val audio = audioConfig?.let { AudioTrackRecorder(it, writer, CameraStream.audioTimebase(c), start, sidecar?.writer).apply { start() } }
            r.startEncoding(video.inputSurface, start)
            take = Take(output, writer, video, audio, sidecar)
            _state.value = CameraState.Recording(profile, SystemClock.elapsedRealtime(), output.displayName)
        } catch (e: Exception) {
            output.finish(keep = false)
            WavSidecar.discard(audioConfig?.wav)
            _state.value = CameraState.Failed("${e.javaClass.simpleName}: ${e.message}", rejected = false)
        }
    }

    /** Ends the take and publishes the file; returns its problems (null when clean). */
    suspend fun stopRecording(): String? = withContext(Dispatchers.Default) {
        val t = take ?: return@withContext null
        take = null
        renderer?.stopEncoding()
        t.audio?.stop()
        t.video.stop()
        t.writer.stop()
        val problem = listOfNotNull(t.video.error, t.audio?.error, t.writer.error, t.audio?.tapError?.let { "WAV: $it" }).firstOrNull()
        val usable = t.writer.isStarted && t.video.encodedFrames.get() > 0
        withContext(Dispatchers.IO) {
            t.output.finish(keep = usable)
            t.wav?.finish(keep = usable)
        }
        (_state.value as? CameraState.Recording)?.let { rec -> _state.update { CameraState.Previewing(rec.profile) } }
        problem ?: if (usable) null else "nothing was recorded"
    }

    /** Frames the encoder has received in this take, for the on-screen counter and drop warnings. */
    fun framesEncoded(): Int = renderer?.framesEncoded?.get() ?: 0

    /** Stops any take and closes the camera (preview off). */
    suspend fun close() {
        if (take != null) stopRecording()
        release()
        _state.value = CameraState.Closed
    }

    private fun release() {
        stream?.close()
        stream = null
        renderer?.release()
        renderer = null
    }

    /** Frees the camera thread; the controller cannot be used afterwards. */
    fun shutdown() {
        release()
        cameraThread.quitSafely()
    }

    companion object {
        private const val FULL_TURN = 360

        /** [uprightRotation] for the camera described by [chars] (also the still capture's `JPEG_ORIENTATION`). */
        fun uprightRotation(chars: CameraCharacteristics, displayRotationDegrees: Int): Int {
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            return if (front) (sensor + displayRotationDegrees) % FULL_TURN else (sensor - displayRotationDegrees + FULL_TURN) % FULL_TURN
        }
    }

    private class Take(val output: VideoOutput, val writer: Mp4Writer, val video: SurfaceVideoEncoder, val audio: AudioTrackRecorder?, val wav: WavSidecar?)
}
