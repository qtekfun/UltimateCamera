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
import com.qtekfun.ultimatecamera.core.codec.ColorTag
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

/**
 * The colour stages of the camera pipeline: [ucLog] converts the 10-bit HLG signal to UC-Log before both LUTs (ignored
 * on 8-bit modes), [recordLut] is baked into the file, [monitorLut] is what the preview shows instead (already chained
 * with [recordLut] when the screen should show the recording plus a viewing LUT).
 */
data class ColorSetup(val ucLog: Boolean, val recordLut: CubeLut?, val monitorLut: CubeLut?) {
    companion object {
        val NONE = ColorSetup(ucLog = false, recordLut = null, monitorLut = null)
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

    // Volatile: setColor may come from another coroutine than open/close (the looks follow the settings).
    @Volatile private var renderer: CameraRenderer? = null
    private var chars: CameraCharacteristics? = null
    private var preview: Surface? = null

    @Volatile private var color = ColorSetup.NONE

    /** What the take in progress bakes in; a change of the recording look waits for the next take. */
    private var takeColor: ColorSetup? = null
    private var previewRotation = 0
    private var monitor = MonitorSettings.OFF

    @Volatile private var take: Take? = null

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
                hlg = profile.tenBit,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            ).also { renderer = it }
            r.setColor(color.ucLog, color.recordLut, color.monitorLut)
            r.previewRotation = previewRotation
            r.setMonitorSettings(monitor)
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

    private fun colorTag(profile: RecordingProfile, setup: ColorSetup): ColorTag? = Companion.colorTag(profile.tenBit, setup)

    private fun pickEncoder(profile: RecordingProfile): EncoderInfo? = CodecCapabilityProbe.pick(encoders, profile.encoderConfig)

    /**
     * Rotation in degrees that makes the open camera's frames upright on a display rotated by [displayRotationDegrees]
     * (`Surface.ROTATION_*` × 90): the MP4 orientation hint, and 180 means the preview must be turned too.
     */
    fun uprightRotation(displayRotationDegrees: Int): Int = chars?.let { uprightRotation(it, displayRotationDegrees) } ?: 0

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

    /**
     * Clockwise turn from the sensor frame (the recorded picture) to the preview on screen: the camera service's
     * `SENSOR_ORIENTATION` turn in the SurfaceTexture transform plus the display compensation of the renderer.
     */
    val previewRotationDegrees: Int
        get() = ((chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) - previewRotation + FULL_TURN) % FULL_TURN

    private fun currentProfile(): RecordingProfile? = when (val s = _state.value) {
        is CameraState.Previewing -> s.profile
        is CameraState.Recording -> s.profile
        else -> null
    }

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

    /** Same LUT on the recording and the preview (null = none). */
    fun setLut(newLut: CubeLut?) = setColor(ColorSetup(ucLog = false, recordLut = newLut, monitorLut = newLut))

    /**
     * The recording and monitor looks (REQ-COL-002/004). During a take only the monitor changes: the recording look is
     * applied from the next take, so a clip never switches look halfway.
     */
    fun setColor(setup: ColorSetup) {
        color = setup
        val applied = takeColor?.let { setup.copy(ucLog = it.ucLog, recordLut = it.recordLut) } ?: setup
        renderer?.setColor(applied.ucLog, applied.recordLut, applied.monitorLut)
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
            val tag = colorTag(profile, color)
            val video = SurfaceVideoEncoder(profile.encoderConfig.copy(colorTag = tag), info.name, writer, ownsWriter = false)
            val start = CameraStream.nowNanos(c)
            val sidecar = audioConfig?.wav?.let { WavSidecar(it, audioConfig) }
            val audio = audioConfig?.let { AudioTrackRecorder(it, writer, CameraStream.audioTimebase(c), start, sidecar?.writer).apply { start() } }
            takeColor = color
            r.startEncoding(video.inputSurface, start, hlgSurface = tag == ColorTag.HLG_BT2020)
            take = Take(output, writer, video, audio, audioConfig, orientationHint, carriedBytes = 0, wav = sidecar)
            _state.value = CameraState.Recording(profile, SystemClock.elapsedRealtime(), output.displayName)
        } catch (e: Exception) {
            takeColor = null
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
        // A recording look chosen during the take applies now.
        if (takeColor != color) renderer?.setColor(color.ucLog, color.recordLut, color.monitorLut)
        takeColor = null
        t.audio?.stop()
        val problem = finishPart(t)
        val usable = t.writer.isStarted && t.video.encodedFrames.get() > 0
        withContext(Dispatchers.IO) { t.wav?.finish(keep = usable || t.carriedBytes > 0) }
        (_state.value as? CameraState.Recording)?.let { rec -> _state.update { CameraState.Previewing(rec.profile) } }
        problem
    }

    /** Ends the (already detached) video encoder and the file of [t]; returns its problems (null when clean). */
    private suspend fun finishPart(t: Take): String? {
        t.video.stop()
        t.writer.stop()
        val problem = listOfNotNull(t.video.error, t.audio?.error, t.writer.error, t.audio?.tapError?.let { "WAV: $it" }).firstOrNull()
        val usable = t.writer.isStarted && t.video.encodedFrames.get() > 0
        withContext(Dispatchers.IO) { t.output.finish(keep = usable) }
        return problem ?: if (usable) null else "nothing was recorded"
    }

    /**
     * Continues the take in a new file (the next part, `…_T002.mp4`, D47) without touching the camera: a new encoder
     * and muxer are prepared, the renderer moves to them between two frames (no video frame is lost), the microphone
     * is handed over (a gap of a few tens of milliseconds in the audio) and the previous part is finished and kept.
     * Returns a problem (null when clean); after a problem the caller stops the take.
     */
    @Suppress("TooGenericExceptionCaught") // Same boundary as startRecording: a vendor codec failure ends the take, not the app.
    suspend fun splitTake(): String? = withContext(Dispatchers.Default) {
        val t = take ?: return@withContext null
        val r = renderer ?: return@withContext null
        val c = chars ?: return@withContext null
        val recording = _state.value as? CameraState.Recording ?: return@withContext null
        val next = try {
            withContext(Dispatchers.IO) { t.output.nextPart() }
        } catch (e: java.io.IOException) {
            return@withContext "next part: ${e.message}"
        }
        val writer: Mp4Writer
        val video: SurfaceVideoEncoder
        try {
            writer = Mp4Writer(next.fileDescriptor, expectedTracks = if (t.audioConfig != null) 2 else 1, orientationHint = t.orientationHint)
            val info = pickEncoder(recording.profile) ?: error("no ${recording.profile.codec} encoder")
            val tag = colorTag(recording.profile, takeColor ?: color)
            video = SurfaceVideoEncoder(recording.profile.encoderConfig.copy(colorTag = tag), info.name, writer, ownsWriter = false)
            r.switchEncoding(video.inputSurface)
        } catch (e: Exception) {
            // The old part still records: drop the new file and let the caller stop the take cleanly.
            withContext(Dispatchers.IO) { next.finish(keep = false) }
            return@withContext "next part: ${e.javaClass.simpleName}: ${e.message}"
        }
        t.audio?.stop()
        var problem: String? = null
        val audio = t.audioConfig?.let { config ->
            try {
                // The take's WAV (REQ-AUD-003) goes on across parts, with the same short gap as the AAC track.
                AudioTrackRecorder(config, writer, CameraStream.audioTimebase(c), CameraStream.nowNanos(c), t.wav?.writer).apply { start() }
            } catch (e: Exception) {
                problem = "audio of the next part: ${e.message}"
                null
            }
        }
        take = Take(next, writer, video, audio, t.audioConfig, t.orientationHint, carriedBytes = t.carriedBytes + t.video.encodedBytes.get(), wav = t.wav)
        _state.value = recording.copy(fileName = next.displayName)
        finishPart(t) ?: problem
    }

    /** Encoded video bytes of the whole take so far (every part), for the bitrate in the status bar. */
    fun videoBytesEncoded(): Long = take?.let { it.carriedBytes + it.video.encodedBytes.get() } ?: 0

    /** Size of the current part's file in bytes (-1 when unknown or not recording), for the FAT32 split. */
    fun partBytes(): Long = take?.output?.bytesWritten() ?: -1

    /** p95 of the recent frame render times on the GL thread, in nanoseconds (0 without a camera). */
    fun renderP95Nanos(): Long = renderer?.renderTimes?.percentile(P95) ?: 0

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

    companion object {
        private const val FULL_TURN = 360
        private const val P95 = 0.95

        /** [uprightRotation] for the camera described by [chars] (also the still capture's `JPEG_ORIENTATION`). */
        fun uprightRotation(chars: CameraCharacteristics, displayRotationDegrees: Int): Int {
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            return if (front) (sensor + displayRotationDegrees) % FULL_TURN else (sensor - displayRotationDegrees + FULL_TURN) % FULL_TURN
        }

        /**
         * Colour signalling of a take: the camera's HLG when the signal is recorded as it comes, SDR transfer in BT.2020
         * for UC-Log, Rec.709 for a baked LUT; 8-bit takes keep the encoder's default (no explicit tag).
         */
        fun colorTag(tenBit: Boolean, setup: ColorSetup): ColorTag? = when {
            !tenBit -> null
            setup.ucLog -> ColorTag.SDR_BT2020
            setup.recordLut != null -> ColorTag.SDR_BT709
            else -> ColorTag.HLG_BT2020
        }
    }

    private class Take(
        val output: VideoOutput,
        val writer: Mp4Writer,
        val video: SurfaceVideoEncoder,
        val audio: AudioTrackRecorder?,
        val audioConfig: AudioConfig?,
        val orientationHint: Int,
        /** Video bytes of the parts already finished. */
        val carriedBytes: Long,
        /** The take's separate WAV, one file across every part. */
        val wav: WavSidecar?
    )
}
