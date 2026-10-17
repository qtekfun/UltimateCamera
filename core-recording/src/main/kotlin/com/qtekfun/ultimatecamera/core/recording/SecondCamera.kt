package com.qtekfun.ultimatecamera.core.recording

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.CameraStream
import com.qtekfun.ultimatecamera.core.camera.StreamSpec
import com.qtekfun.ultimatecamera.core.codec.CodecCapabilityProbe
import com.qtekfun.ultimatecamera.core.codec.EncoderConfig
import com.qtekfun.ultimatecamera.core.codec.Mp4Writer
import com.qtekfun.ultimatecamera.core.codec.SurfaceVideoEncoder
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Why a camera pair cannot be used now (D62). */
enum class PairBlock {
    /** The pair does not contain the main camera open now (another lens is selected). */
    OTHER_MAIN_CAMERA,

    /** The phone refused to stream the partner next to the main camera in its current mode. */
    REFUSED
}

/** A pair of cameras the phone can stream together ([ids], the main one first when it is in it); [block] null = usable. */
data class PairOption(val ids: List<String>, val block: PairBlock? = null) {
    val enabled: Boolean get() = block == null

    /** Stored form of the choice (`0,1`): the ids sorted, so it does not depend on which one is main. */
    val key: String get() = ConcurrentPairs.key(ids)
}

/** Pure choices for the second local camera (REQ-NET-001, D59, D62). */
object ConcurrentPairs {
    /** Stored form of a pair. */
    fun key(ids: Collection<String>): String = ids.sortedWith(ID_ORDER).joinToString(",")

    /**
     * Every pair of cameras inside the combinations [sets] the phone reports, usable ones first; a pair without [mainId]
     * is blocked ([PairBlock.OTHER_MAIN_CAMERA]), one whose partner is in [refused] is [PairBlock.REFUSED].
     */
    fun options(sets: Set<Set<String>>, mainId: String?, refused: Set<String> = emptySet()): List<PairOption> {
        val pairs = sets.flatMap { set ->
            val ids = set.sortedWith(ID_ORDER)
            ids.indices.flatMap { i -> (i + 1 until ids.size).map { j -> listOf(ids[i], ids[j]) } }
        }.distinctBy { key(it) }
        return pairs.map { pair ->
            when {
                mainId == null || mainId !in pair -> PairOption(pair, PairBlock.OTHER_MAIN_CAMERA)
                (pair - mainId).first() in refused -> PairOption(listOf(mainId) + (pair - mainId), PairBlock.REFUSED)
                else -> PairOption(listOf(mainId) + (pair - mainId))
            }
        }.sortedWith(compareBy<PairOption> { it.block != null }.thenBy { it.key })
    }

    /**
     * The partner of [mainId]: the other camera of the [chosen] pair (its [key]) when the phone reports that pair and it
     * holds [mainId], else the default of [partner] (front first).
     */
    fun partner(sets: Set<Set<String>>, mainId: String, chosen: String?, isFront: (String) -> Boolean): String? {
        val pair = chosen?.split(",")?.takeIf { it.size == 2 && mainId in it }
        val reported = pair != null && sets.any { it.containsAll(pair) }
        return if (pair != null && reported) (pair - mainId).first() else partner(sets, mainId, isFront)
    }

    private val ID_ORDER = compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }

    /**
     * The camera to stream next to [mainId], from the phone's concurrent combinations [sets]
     * (`CameraManager.getConcurrentCameraIds`): a front camera first (the classic interview / vlog pair), then any other,
     * lowest id first. Null when no combination contains [mainId].
     */
    fun partner(sets: Set<Set<String>>, mainId: String, isFront: (String) -> Boolean): String? {
        val candidates = sets.filter { mainId in it }.flatMap { it - mainId }.distinct()
        return candidates.sortedWith(compareByDescending<String> { isFront(it) }.thenBy { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }).firstOrNull()
    }

    /**
     * The stream size for the second camera: [TARGET] (1080p) when offered, else the largest 16:9 size under it, else the
     * largest size under it. Concurrent streaming is only guaranteed at modest sizes, so never above 1080p.
     */
    fun size(offered: List<Pair<Int, Int>>): Pair<Int, Int>? {
        val fitting = offered.filter { (w, h) -> w <= TARGET.first && h <= TARGET.second }
        if (TARGET in fitting) return TARGET
        val wide = fitting.filter { (w, h) -> w * WIDE_H == h * WIDE_W }
        return (wide.ifEmpty { fitting }).maxByOrNull { (w, h) -> w * h }
    }

    val TARGET = 1920 to 1080
    const val FPS = 30
    private const val WIDE_W = 16
    private const val WIDE_H = 9
}

/**
 * A second camera recorded as its own file next to the main take (experimental, REQ-NET-001, D59): 1080p30 8-bit with
 * its own [CameraRenderer], encoder and file named after its camera id, shown as a picture-in-picture preview. Video
 * only (the main take carries the audio). Call from one coroutine scope.
 */
class SecondCamera(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("uc-camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private val encoders by lazy { CodecCapabilityProbe.encoders() }

    private var stream: CameraStream? = null
    private var renderer: CameraRenderer? = null
    private var chars: CameraCharacteristics? = null
    private var preview: Surface? = null
    private var take: Pair<Mp4Writer, SurfaceVideoEncoder>? = null
    private var output: VideoOutput? = null
    private var size = ConcurrentPairs.TARGET

    /** The open second camera's id, or null. */
    @Volatile var cameraId: String? = null
        private set

    /**
     * The camera to pair with [mainId] on this phone (the other camera of the [chosen] pair when it applies, D62), or null
     * when it reports no concurrent combination with it.
     */
    fun partnerFor(mainId: String, chosen: String? = null): String? = runCatching {
        ConcurrentPairs.partner(manager.concurrentCameraIds, mainId, chosen) {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
    }.getOrNull()

    /** The camera combinations the phone reports (`getConcurrentCameraIds`), empty when none or unreadable. */
    fun concurrentSets(): Set<Set<String>> = runCatching { manager.concurrentCameraIds }.getOrDefault(emptySet())

    /** Opens [id] streaming into its own renderer; returns a problem, or null. */
    @Suppress("TooGenericExceptionCaught") // Vendor HALs throw anything: the experiment fails, the main camera goes on.
    suspend fun open(id: String): String? = withContext(Dispatchers.Default) {
        close()
        try {
            val c = manager.getCameraCharacteristics(id).also { chars = it }
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            size = ConcurrentPairs.size(sizes.map { it.width to it.height }) ?: return@withContext "no stream size on camera $id"
            val r = CameraRenderer(
                EglColorDepth.RGBA8888,
                size.first,
                size.second,
                hlg = false,
                sensorOrientation =
                    c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            )
            renderer = r
            preview?.let(r::setPreview)
            stream = CameraStream.open(manager, StreamSpec(id, size.first, size.second, ConcurrentPairs.FPS), r.cameraSurface, handler) { }
            cameraId = id
            null
        } catch (e: Exception) {
            close()
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** The picture-in-picture view's surface (null when it goes away). */
    fun setPreview(surface: Surface?) {
        preview = surface
        renderer?.setPreview(surface)
    }

    /** The display's rotation in degrees, as for the main preview. */
    fun setPreviewRotation(degrees: Int) {
        renderer?.previewRotation = degrees
    }

    /**
     * Starts recording into [out] (a `…_CAM<id>_…` take) upright for a display rotated by [displayRotationDegrees];
     * returns a problem, or null.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun startRecording(out: VideoOutput, displayRotationDegrees: Int): String? = withContext(Dispatchers.Default) {
        val r = renderer
        val c = chars
        if (r == null || c == null) {
            withContext(Dispatchers.IO) { out.finish(keep = false) }
            return@withContext "second camera not open"
        }
        val orientationHint = CameraController.uprightRotation(c, displayRotationDegrees)
        try {
            val config = encoderConfig()
            val info = CodecCapabilityProbe.pick(encoders, config) ?: error("no encoder for ${config.label}")
            val writer = Mp4Writer(out.fileDescriptor, expectedTracks = 1, orientationHint = orientationHint)
            val video = SurfaceVideoEncoder(config, info.name, writer, ownsWriter = false)
            r.startEncoding(video.inputSurface, CameraStream.nowNanos(c), hlgSurface = false)
            take = writer to video
            output = out
            null
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { out.finish(keep = false) }
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** HEVC 8-bit where a hardware encoder exists (half the bitrate of H.264), else H.264. */
    private fun encoderConfig(): EncoderConfig {
        val codec = if (encoders.any { it.codec == VideoCodec.HEVC && it.hardware }) VideoCodec.HEVC else VideoCodec.H264
        val (w, h) = size
        return EncoderConfig(
            codec,
            w,
            h,
            ConcurrentPairs.FPS,
            RecordingProfile.defaultBitrate(w, h, ConcurrentPairs.FPS, tenBit = false, codec = codec),
            tenBit = false
        )
    }

    /** Ends the take and keeps its file when something was written; returns its problem, or null. */
    suspend fun stopRecording(): String? = withContext(Dispatchers.Default) {
        val (writer, video) = take ?: return@withContext null
        val out = output
        take = null
        output = null
        renderer?.stopEncoding()
        video.stop()
        writer.stop()
        val usable = writer.isStarted && video.encodedFrames.get() > 0
        withContext(Dispatchers.IO) { out?.finish(keep = usable) }
        listOfNotNull(video.error, writer.error).firstOrNull() ?: if (usable) null else "nothing was recorded"
    }

    /** Stops any take and closes the second camera. */
    suspend fun close() {
        if (take != null) stopRecording()
        stream?.close()
        stream = null
        renderer?.release()
        renderer = null
        cameraId = null
    }

    /** Frees the camera thread; this object cannot be used afterwards. */
    fun shutdown() {
        thread.quitSafely()
    }
}
