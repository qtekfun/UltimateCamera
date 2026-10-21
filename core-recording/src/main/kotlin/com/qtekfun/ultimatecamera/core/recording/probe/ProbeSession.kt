package com.qtekfun.ultimatecamera.core.recording.probe

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import com.qtekfun.ultimatecamera.core.camera.CameraStream
import com.qtekfun.ultimatecamera.core.camera.StreamSpec
import com.qtekfun.ultimatecamera.core.capabilities.DirectMode
import com.qtekfun.ultimatecamera.core.capabilities.ProbeMeasurement
import com.qtekfun.ultimatecamera.core.capabilities.ProbeRequest
import com.qtekfun.ultimatecamera.core.capabilities.SessionStatus
import com.qtekfun.ultimatecamera.core.capabilities.ThermalMonitor
import com.qtekfun.ultimatecamera.core.capabilities.ThermalStatus
import com.qtekfun.ultimatecamera.core.capabilities.VendorParameter
import com.qtekfun.ultimatecamera.core.codec.EncoderConfig
import com.qtekfun.ultimatecamera.core.codec.SurfaceVideoEncoder
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The encoder refused the format: reported as [SessionStatus.ENCODER_UNSUPPORTED]. */
internal class EncoderRefusedException(cause: Exception) : RuntimeException("${cause.javaClass.simpleName}: ${cause.message}", cause)

/** What one probe needs once the cheap checks passed. */
internal class ProbeSetup(
    val request: ProbeRequest,
    val encoderConfig: EncoderConfig,
    val codecName: String,
    val dynamicRangeProfile: Long?,
    val vendor: List<VendorParameter>,
    /** `SENSOR_ORIENTATION`, so the encoder gets the sensor's frame as in a real take. */
    val sensorOrientation: Int = 0
)

/** One probe's camera, GL stage, encoder and temporary file; [release] frees all of them, whatever was opened. */
internal class ProbeSession(private val manager: CameraManager, private val thermal: ThermalMonitor, private val tempDir: File, private val setup: ProbeSetup) {
    private val thread = HandlerThread("uc-probe").apply { start() }
    private var renderer: CameraRenderer? = null
    private var encoder: SurfaceVideoEncoder? = null
    private var stream: CameraStream? = null
    private var file: File? = null
    private var preview: ImageReader? = null
    private val lost = AtomicReference<String?>(null)

    var thermalMax = thermal.status()
        private set

    suspend fun measure(): ProbeMeasurement {
        val request = setup.request
        val config = request.config
        if (DirectMode.isDirect(config)) return measureDirect()
        if (request.checkDeliveredSize) {
            val wanted = "${config.width}x${config.height}"
            val delivered = deliveredSize()
            if (delivered != wanted) {
                val error = delivered?.let { "camera delivers $it for $wanted" } ?: "no frame of $wanted"
                return ProbeMeasurement(SessionStatus.WRONG_SIZE, thermalStatusMax = thermalMax, error = error, deliveredSize = delivered)
            }
        }
        val interpolation = ProbeMapping.interpolation(config.lut)
        val r = CameraRenderer(
            depth = if (config.tenBit) EglColorDepth.RGBA1010102 else EglColorDepth.RGBA8888,
            width = config.width,
            height = config.height,
            hlg = config.tenBit,
            sensorOrientation = setup.sensorOrientation,
            interpolation = interpolation ?: LutInterpolation.HARDWARE,
            sourceWidth = config.streamWidth,
            sourceHeight = config.streamHeight
        ).also { renderer = it }
        if (interpolation != null) r.setLut(CubeLut.identity(LUT_SIZE))
        val out = withContext(Dispatchers.IO) {
            tempDir.mkdirs()
            File.createTempFile("probe", ".mp4", tempDir)
        }.also { file = it }
        val enc = openEncoder(out).also { encoder = it }
        r.expectedFrameIntervalNanos = NANOS_PER_SECOND / config.fps
        r.startEncoding(enc.inputSurface, 0)
        val spec = StreamSpec(
            request.cameraId,
            config.streamWidth,
            config.streamHeight,
            config.fps,
            setup.dynamicRangeProfile,
            sessionType = request.sessionType,
            recordUseCase = request.recordUseCase
        )
        val s = CameraStream.open(manager, spec, r.cameraSurface, Handler(thread.looper), extras = { b -> setup.vendor.forEach { b.setVendor(it) } }) {
            lost.compareAndSet(null, it)
        }
        stream = s
        watch(request.warmupMs, stopWhenSevere = false)
        val startClock = s.frameClock()
        val startFrames = enc.encodedFrames.get()
        val startPts = enc.lastPtsUs.get()
        val gapCounter = r.encoderGaps
        val startGaps = gapCounter.drops.get()
        val startGapCount = gapCounter.gaps.get()
        gapCounter.startWindow()
        watch(request.measureMs, stopWhenSevere = true)
        val gaps = gapCounter.drops.get() - startGaps
        val gapStats = GapStats.of(gapCounter.gaps.get() - startGapCount, gapCounter.maxStepNanos)
        val endClock = s.frameClock()
        val cameraFps = endClock.fpsSince(startClock)
        val skips = endClock.skipped - startClock.skipped
        val buffersLost = endClock.buffersLost - startClock.buffersLost
        val window = FrameWindow.of(startFrames, startPts, enc.encodedFrames.get(), enc.lastPtsUs.get(), config.fps, gaps, skips)
        val lostReason = lost.get()
        return when {
            lostReason != null -> ProbeMeasurement(SessionStatus.CAMERA_ERROR, thermalStatusMax = thermalMax, error = lostReason)
            enc.error != null && enc.encodedFrames.get() == 0 ->
                ProbeMeasurement(SessionStatus.ENCODER_UNSUPPORTED, thermalStatusMax = thermalMax, error = enc.error)
            else -> ProbeMeasurement(
                SessionStatus.RAN,
                window.measuredFps,
                window.droppedFrames,
                cameraFps,
                thermalMax,
                r.error ?: enc.error,
                gapCount = gapStats.count,
                maxGapMs = gapStats.maxMs,
                cameraSkips = skips,
                buffersLost = buffersLost
            )
        }
    }

    /**
     * Direct mode (D71): the camera outputs straight into the encoder plus a ≤1920-wide preview stream (an ImageReader
     * that drops its frames, standing in for the viewfinder); no GL pass, so gaps come from the camera timestamps only.
     */
    private suspend fun measureDirect(): ProbeMeasurement {
        val config = setup.request.config
        val (pw, ph) = DirectMode.previewSize(config.width, config.height)
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(pw, ph, ImageFormat.PRIVATE, PREVIEW_BUFFERS).also { preview = it }
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
        val out = withContext(Dispatchers.IO) {
            tempDir.mkdirs()
            File.createTempFile("probe", ".mp4", tempDir)
        }.also { file = it }
        val enc = openEncoder(out).also { encoder = it }
        val spec = StreamSpec(
            setup.request.cameraId,
            config.width,
            config.height,
            config.fps,
            setup.dynamicRangeProfile,
            recordUseCase = setup.request.recordUseCase
        )
        val s = CameraStream.open(manager, spec, reader.surface, handler, recordOutput = enc.inputSurface) { lost.compareAndSet(null, it) }
        stream = s
        s.setRecording(true)
        watch(setup.request.warmupMs, stopWhenSevere = false)
        val startClock = s.frameClock()
        val startFrames = enc.encodedFrames.get()
        val startPts = enc.lastPtsUs.get()
        watch(setup.request.measureMs, stopWhenSevere = true)
        val endClock = s.frameClock()
        val cameraFps = endClock.fpsSince(startClock)
        val skips = endClock.skipped - startClock.skipped
        val buffersLost = endClock.buffersLost - startClock.buffersLost
        val window = FrameWindow.of(startFrames, startPts, enc.encodedFrames.get(), enc.lastPtsUs.get(), config.fps, cameraSkips = skips)
        val lostReason = lost.get()
        return when {
            lostReason != null -> ProbeMeasurement(SessionStatus.CAMERA_ERROR, thermalStatusMax = thermalMax, error = lostReason)
            enc.error != null && enc.encodedFrames.get() == 0 ->
                ProbeMeasurement(SessionStatus.ENCODER_UNSUPPORTED, thermalStatusMax = thermalMax, error = enc.error)
            else -> ProbeMeasurement(
                SessionStatus.RAN,
                window.measuredFps,
                window.droppedFrames,
                cameraFps,
                thermalMax,
                enc.error,
                cameraSkips = skips,
                buffersLost = buffersLost
            )
        }
    }

    /**
     * The size of the frames the camera really delivers for an unlisted size (D110), read from the first frame's buffer
     * in a texture-usage reader (the consumer the GPU path gives the camera, which the framework may hand the nearest
     * listed size instead). Null when no frame came, or the reader refused a buffer of another size. 8-bit: only the
     * size matters here.
     */
    // A reader refusing a buffer of another size throws: that answers the question, so the exception itself is not kept.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun deliveredSize(): String? {
        val config = setup.request.config
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(config.width, config.height, ImageFormat.PRIVATE, SIZE_CHECK_BUFFERS, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val size = CompletableDeferred<String?>()
        reader.setOnImageAvailableListener({ r ->
            try {
                r.acquireLatestImage()?.use { image ->
                    val buffer = image.hardwareBuffer
                    size.complete(buffer?.let { "${it.width}x${it.height}" } ?: "${image.width}x${image.height}")
                    buffer?.close()
                }
            } catch (e: Exception) {
                size.complete(null)
            }
        }, handler)
        val spec = StreamSpec(setup.request.cameraId, config.width, config.height, config.fps)
        var check: CameraStream? = null
        return try {
            check = CameraStream.open(manager, spec, reader.surface, handler) { lost.compareAndSet(null, it) }
            withTimeoutOrNull(SIZE_CHECK_MS) { size.await() }
        } finally {
            runCatching { check?.close() }
            reader.close()
        }
    }

    private fun openEncoder(out: File): SurfaceVideoEncoder = try {
        SurfaceVideoEncoder(setup.encoderConfig, setup.codecName, out)
    } catch (e: MediaCodec.CodecException) {
        throw EncoderRefusedException(e)
    } catch (e: IllegalArgumentException) {
        throw EncoderRefusedException(e)
    } catch (e: IllegalStateException) {
        throw EncoderRefusedException(e)
    }

    /** Waits [ms] sampling the thermal status; stops early when the camera is lost (and, if asked, at SEVERE). */
    private suspend fun watch(ms: Long, stopWhenSevere: Boolean) {
        val end = System.nanoTime() + ms * NANOS_PER_MS
        fun tooHot() = stopWhenSevere && thermalMax >= ThermalStatus.SEVERE
        while (System.nanoTime() < end && lost.get() == null && !tooHot()) {
            delay(POLL_MS)
            thermalMax = maxOf(thermalMax, thermal.status())
        }
    }

    /** Camera first (no more frames), then the encoder (end of stream), then GL; the temporary file is deleted. */
    suspend fun release() {
        runCatching { stream?.close() }
        runCatching { renderer?.stopEncoding() }
        runCatching { encoder?.stop() }
        runCatching { renderer?.release() }
        runCatching { preview?.close() }
        thread.quitSafely()
        withContext(Dispatchers.IO) { file?.delete() }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val LUT_SIZE = 33
        const val POLL_MS = 100L
        const val NANOS_PER_MS = 1_000_000L
        const val PREVIEW_BUFFERS = 4
        const val SIZE_CHECK_BUFFERS = 2
        const val SIZE_CHECK_MS = 3_000L
    }
}
