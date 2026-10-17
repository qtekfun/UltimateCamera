package com.qtekfun.ultimatecamera.core.recording

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.CameraControls
import com.qtekfun.ultimatecamera.core.camera.CameraStream
import com.qtekfun.ultimatecamera.core.camera.ControlMath
import com.qtekfun.ultimatecamera.core.camera.ControlRanges
import com.qtekfun.ultimatecamera.core.camera.LiveExposure
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
    val bitrateBps: Int = defaultBitrate(width, height, fps, tenBit),
    val stabilization: Boolean = false
) {
    val encoderConfig: EncoderConfig get() = EncoderConfig(codec, width, height, fps, bitrateBps, tenBit)

    val label: String get() = "${sizeLabel(height)}$fps${if (tenBit) " 10-bit" else ""}"

    companion object {
        fun sizeLabel(height: Int): String = when {
            height >= 4320 -> "8K"
            height >= 2160 -> "4K"
            else -> "${height}p"
        }

        /** Bitrates measured to look clean in the spike (4K60 10-bit ≈ 100 Mbit/s), scaled by pixels per second. */
        fun defaultBitrate(width: Int, height: Int, fps: Int, tenBit: Boolean): Int {
            val pixelsPerSecond = width.toLong() * height * fps
            val bitsPerPixel = if (tenBit) 0.2 else 0.17
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
    private var previewRotated180 = false

    private var take: Take? = null

    private val _controls = MutableStateFlow(CameraControls())
    private val _ranges = MutableStateFlow<ControlRanges?>(null)
    private val _live = MutableStateFlow(LiveExposure())

    /** The manual controls asked for (REQ-CAM-002); kept across profile changes and re-applied to each new stream. */
    val controls: StateFlow<CameraControls> = _controls.asStateFlow()

    /** What the open camera lets the user control (null while closed). */
    val ranges: StateFlow<ControlRanges?> = _ranges.asStateFlow()

    /** ISO, shutter and focus the camera is using now, also in auto (about 10 Hz). */
    val live: StateFlow<LiveExposure> = _live.asStateFlow()

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
                hlg = profile.tenBit
            ).also { renderer = it }
            r.setLut(lut)
            r.previewRotated180 = previewRotated180
            preview?.let(r::setPreview)
            val spec = StreamSpec(cameraId, profile.width, profile.height, profile.fps, tenBitProfile, profile.stabilization)
            val s = CameraStream.open(manager, spec, r.cameraSurface, cameraHandler) { reason -> _state.value = CameraState.Failed(reason, rejected = false) }
            stream = s
            s.onLive = { _live.value = it }
            _ranges.value = s.ranges
            // A tap point belongs to the previous framing; the rest of the controls carry over, clamped to this camera.
            _controls.value = s.ranges.clamp(_controls.value.copy(meteringPoint = null, afLock = false), profile.fps)
            s.setControls(_controls.value)
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
    fun uprightRotation(displayRotationDegrees: Int): Int {
        val c = chars ?: return 0
        val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        return if (front) (sensor + displayRotationDegrees) % FULL_TURN else (sensor - displayRotationDegrees + FULL_TURN) % FULL_TURN
    }

    /** Applies [requested] (clamped to the camera's ranges) to the running stream, without reconfiguring it. */
    fun setControls(requested: CameraControls) {
        val profile = currentProfile()
        val ranges = _ranges.value
        val clamped = if (profile != null && ranges != null) ranges.clamp(requested, profile.fps) else requested
        _controls.value = clamped
        stream?.setControls(clamped)
    }

    /**
     * Focuses and meters at a touch ([x], [y]) on the [viewWidth]×[viewHeight] preview; [lock] also locks AE and AF
     * there. Touches in the letterbox bars are ignored (false).
     */
    fun meterAt(x: Float, y: Float, viewWidth: Int, viewHeight: Int, lock: Boolean): Boolean {
        val profile = currentProfile() ?: return false
        val point = ControlMath.previewToImage(x, y, viewWidth, viewHeight, profile.width, profile.height, previewRotationDegrees) ?: return false
        setControls(_controls.value.copy(meteringPoint = point, afLock = lock, aeLock = lock))
        return true
    }

    /** Whether the preview is drawn upside down now (to map points back onto the view). */
    val previewRotationDegrees: Int get() = if (previewRotated180) 180 else 0

    private fun currentProfile(): RecordingProfile? = when (val s = _state.value) {
        is CameraState.Previewing -> s.profile
        is CameraState.Recording -> s.profile
        else -> null
    }

    /** Turns the preview upside down (device held in the landscape orientation opposite to the sensor's). */
    fun setPreviewRotated180(rotated: Boolean) {
        previewRotated180 = rotated
        renderer?.previewRotated180 = rotated
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

    /** Starts a take into [output] with [audioConfig] (null = video only). */
    @Suppress("TooGenericExceptionCaught")
    suspend fun startRecording(output: VideoOutput, audioConfig: AudioConfig?, orientationHint: Int) = withContext(Dispatchers.Default) {
        val current = _state.value as? CameraState.Previewing ?: return@withContext
        val r = renderer ?: return@withContext
        val c = chars ?: return@withContext
        val profile = current.profile
        try {
            val writer = Mp4Writer(output.fileDescriptor, expectedTracks = if (audioConfig != null) 2 else 1, orientationHint = orientationHint)
            val info = pickEncoder(profile) ?: error("no ${profile.codec} encoder for ${profile.label}")
            val video = SurfaceVideoEncoder(profile.encoderConfig, info.name, writer, ownsWriter = false)
            val start = CameraStream.nowNanos(c)
            val audio = audioConfig?.let { AudioTrackRecorder(it, writer, CameraStream.audioTimebase(c), start).apply { start() } }
            r.startEncoding(video.inputSurface, start)
            take = Take(output, writer, video, audio)
            _state.value = CameraState.Recording(profile, SystemClock.elapsedRealtime(), output.displayName)
        } catch (e: Exception) {
            output.finish(keep = false)
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
        val problem = listOfNotNull(t.video.error, t.audio?.error, t.writer.error).firstOrNull()
        val usable = t.writer.isStarted && t.video.encodedFrames.get() > 0
        withContext(Dispatchers.IO) { t.output.finish(keep = usable) }
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
        _ranges.value = null
        _live.value = LiveExposure()
        renderer?.release()
        renderer = null
    }

    /** Frees the camera thread; the controller cannot be used afterwards. */
    fun shutdown() {
        release()
        cameraThread.quitSafely()
    }

    private companion object {
        const val FULL_TURN = 360
    }

    private class Take(val output: VideoOutput, val writer: Mp4Writer, val video: SurfaceVideoEncoder, val audio: AudioTrackRecorder?)
}
