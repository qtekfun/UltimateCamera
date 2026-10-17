package com.qtekfun.ultimatecamera.core.recording.probe

import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import com.qtekfun.ultimatecamera.core.camera.CameraStream
import com.qtekfun.ultimatecamera.core.camera.StreamSpec
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    private val lost = AtomicReference<String?>(null)

    var thermalMax = thermal.status()
        private set

    suspend fun measure(): ProbeMeasurement {
        val request = setup.request
        val config = request.config
        val interpolation = ProbeMapping.interpolation(config.lut)
        val r = CameraRenderer(
            depth = if (config.tenBit) EglColorDepth.RGBA1010102 else EglColorDepth.RGBA8888,
            width = config.width,
            height = config.height,
            hlg = config.tenBit,
            sensorOrientation = setup.sensorOrientation,
            interpolation = interpolation ?: LutInterpolation.HARDWARE
        ).also { renderer = it }
        if (interpolation != null) r.setLut(CubeLut.identity(LUT_SIZE))
        val out = withContext(Dispatchers.IO) {
            tempDir.mkdirs()
            File.createTempFile("probe", ".mp4", tempDir)
        }.also { file = it }
        val enc = openEncoder(out).also { encoder = it }
        r.expectedFrameIntervalNanos = NANOS_PER_SECOND / config.fps
        r.startEncoding(enc.inputSurface, 0)
        val spec = StreamSpec(request.cameraId, config.width, config.height, config.fps, setup.dynamicRangeProfile, sessionType = request.sessionType)
        stream = CameraStream.open(manager, spec, r.cameraSurface, Handler(thread.looper), extras = { b -> setup.vendor.forEach { b.setVendor(it) } }) {
            lost.compareAndSet(null, it)
        }
        watch(request.warmupMs, stopWhenSevere = false)
        val startFrames = enc.encodedFrames.get()
        val startPts = enc.lastPtsUs.get()
        val startGaps = r.encoderGapDrops.get()
        watch(request.measureMs, stopWhenSevere = true)
        val gaps = r.encoderGapDrops.get() - startGaps
        val window = FrameWindow.of(startFrames, startPts, enc.encodedFrames.get(), enc.lastPtsUs.get(), config.fps, gaps)
        val lostReason = lost.get()
        return when {
            lostReason != null -> ProbeMeasurement(SessionStatus.CAMERA_ERROR, thermalStatusMax = thermalMax, error = lostReason)
            enc.error != null && enc.encodedFrames.get() == 0 ->
                ProbeMeasurement(SessionStatus.ENCODER_UNSUPPORTED, thermalStatusMax = thermalMax, error = enc.error)
            else -> ProbeMeasurement(SessionStatus.RAN, window.measuredFps, window.droppedFrames, thermalMax, r.error ?: enc.error)
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
        thread.quitSafely()
        withContext(Dispatchers.IO) { file?.delete() }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val LUT_SIZE = 33
        const val POLL_MS = 100L
        const val NANOS_PER_MS = 1_000_000L
    }
}
