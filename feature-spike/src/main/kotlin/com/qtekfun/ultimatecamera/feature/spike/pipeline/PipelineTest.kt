package com.qtekfun.ultimatecamera.feature.spike.pipeline

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.CameraNames
import com.qtekfun.ultimatecamera.core.camera.SessionRejectedException
import com.qtekfun.ultimatecamera.core.camera.createSession
import com.qtekfun.ultimatecamera.core.camera.openCameraDevice
import com.qtekfun.ultimatecamera.core.codec.CodecCapabilityProbe
import com.qtekfun.ultimatecamera.core.codec.EncoderConfig
import com.qtekfun.ultimatecamera.core.codec.EncoderInfo
import com.qtekfun.ultimatecamera.core.codec.SurfaceVideoEncoder
import com.qtekfun.ultimatecamera.core.codec.VideoCodec
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.feature.spike.ThermalSample
import com.qtekfun.ultimatecamera.feature.spike.ThermalSampler
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class PipelineConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: VideoCodec,
    val tenBit: Boolean,
    /** Null records without a LUT pass (only the copy into the encoder), to isolate the LUT's cost. */
    val lut: LutInterpolation?,
    val bitrateMbps: Int,
    val preview: Boolean = true
) {
    val label: String get() = "${width}x$height@$fps ${codec.name} ${if (tenBit) "10-bit" else "8-bit"} ${lut?.name ?: "NO_LUT"}"
}

@Serializable
data class PipelineResult(
    val label: String,
    val config: PipelineConfig,
    val cameraId: String,
    val supported: Boolean,
    val reason: String? = null,
    val dynamicRangeProfile: String? = null,
    val aeFpsRange: String? = null,
    val encoder: String? = null,
    val eglRecordable: Boolean? = null,
    val eglHlgColorspace: Boolean? = null,
    val previewError: String? = null,
    /** The remote-monitor proxy could not be created or stopped (null when there was no proxy or it worked). */
    val proxyError: String? = null,
    val measuredSeconds: Double = 0.0,
    /** Cadence of the sensor (capture results): drops here are the camera's. */
    val sensor: FrameStats? = null,
    /** Cadence of the frames the GPU rendered: drops here but not at the sensor mean the render thread fell behind. */
    val gpu: FrameStats? = null,
    val gpuFramesTotal: Int = 0,
    val encodedFramesTotal: Int = 0,
    val encodedFps: Double = 0.0,
    val captureFailures: Int = 0,
    val buffersLost: Int = 0,
    val renderCpu: DurationStats? = null,
    val encodedMbps: Double = 0.0,
    val thermal: List<ThermalSample> = emptyList(),
    val outputFile: String? = null,
    val outputFormat: String? = null,
    /** Screen forced to full brightness during the run (it heats the phone, so thermal results are not comparable). */
    val maxBrightness: Boolean = false,
    /** Target frame rate held with no dropped frame at the sensor, the GPU or the encoder. */
    val meetsTarget: Boolean = false,
    /** The HAL refused this configuration when the session was created (a device limit, not a crash). */
    val sessionRejected: Boolean = false,
    val error: String? = null
)

/**
 * Phase 0 measurement: Camera2 (10-bit profile when asked) → GL (LUT) → MediaCodec → MP4, plus the preview, on the main
 * back camera. Reports frame cadence at each stage, render-thread cost, bitrate and temperature.
 */
class PipelineTest(private val context: Context, private val encoders: List<EncoderInfo>, private val outputDir: File) {
    private val manager = context.getSystemService(CameraManager::class.java)

    @Suppress("LongMethod", "TooGenericExceptionCaught") // Probe boundary: any vendor failure is a result, not a crash.
    suspend fun run(
        config: PipelineConfig,
        lut: CubeLut,
        previewSurface: Surface?,
        maxBrightness: Boolean = false,
        warmupSeconds: Int = 2,
        durationSeconds: Int = 20,
        proxy: ProxyOutput? = null,
        onMeasuring: () -> Unit = {},
        onProgress: (String) -> Unit = {}
    ): PipelineResult {
        val cameraId = mainBackCamera() ?: return PipelineResult(config.label, config, "", supported = false, reason = "no back camera")
        val base = PipelineResult(config.label, config, cameraId, supported = true, maxBrightness = maxBrightness)
        val chars = manager.getCameraCharacteristics(cameraId)
        val plan = try {
            plan(config, chars)
        } catch (e: Unsupported) {
            return base.copy(supported = false, reason = e.message)
        }
        val encoderConfig = EncoderConfig(config.codec, config.width, config.height, config.fps, config.bitrateMbps * 1_000_000, config.tenBit)
        val encoderInfo = CodecCapabilityProbe.pick(encoders, encoderConfig)
            ?: return base.copy(supported = false, reason = "no ${config.codec} encoder${if (config.tenBit) " with a 10-bit profile" else ""}")
        val withPlan = base.copy(dynamicRangeProfile = plan.profileName, aeFpsRange = plan.fpsRange.toString(), encoder = encoderInfo.name)

        val glThread = HandlerThread("uc-gl").apply { start() }
        val cameraThread = HandlerThread("uc-camera").apply { start() }
        val glHandler = Handler(glThread.looper)
        val cameraHandler = Handler(cameraThread.looper)
        val glDispatcher = glHandler.asCoroutineDispatcher()
        val cameraExecutor = Executor { cameraHandler.post(it) }
        val capacity = config.fps * (warmupSeconds + durationSeconds + 5)
        val sensorLog = TimestampLog(capacity)
        val failures = AtomicInteger()
        val lost = AtomicInteger()
        val file = File(outputDir, "pipeline-${config.label.replace(' ', '_')}.mp4")
        val thermal = mutableListOf<ThermalSample>()
        val sampler = ThermalSampler(context)

        var encoder: SurfaceVideoEncoder? = null
        var gl: GlPipeline? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val measuring = AtomicBoolean(false)
        val cameraLost = AtomicReference<String?>(null)
        try {
            onProgress("${config.label} …")
            val enc = SurfaceVideoEncoder(encoderConfig, encoderInfo.name, file).also { encoder = it }
            val pipeline = withContext(glDispatcher) {
                GlPipeline(
                    depth = if (config.tenBit) EglColorDepth.RGBA1010102 else EglColorDepth.RGBA8888,
                    encoderSurface = enc.inputSurface,
                    previewSurface = previewSurface.takeIf { config.preview },
                    lut = lut,
                    interpolation = config.lut,
                    width = config.width,
                    height = config.height,
                    hlg = plan.profile == DynamicRangeProfiles.HLG10,
                    capacity = capacity,
                    handler = glHandler,
                    proxy = proxy
                )
            }.also { gl = it }

            val camera = manager.openCameraDevice(cameraId, cameraHandler) { cameraLost.set(it) }.also { device = it }
            val counters = CaptureCounters(sensorLog, failures, lost, measuring)
            val captureSession = startCapture(camera, pipeline.cameraSurface, plan, config, cameraExecutor, cameraHandler, counters).also { session = it }

            delay(warmupSeconds * 1_000L)
            val start = SystemClock.elapsedRealtime()
            measuring.set(true)
            pipeline.measuring = true
            onMeasuring()
            coroutineScope {
                val sampling = launch {
                    while (isActive) {
                        thermal += sampler.sample()
                        onProgress("${config.label}: ${(SystemClock.elapsedRealtime() - start) / 1_000}/$durationSeconds s")
                        delay(1_000)
                    }
                }
                delay(durationSeconds * 1_000L)
                sampling.cancel()
            }
            measuring.set(false)
            pipeline.measuring = false
            thermal += sampler.sample()
            val seconds = (SystemClock.elapsedRealtime() - start) / 1_000.0

            captureSession.stopRepeating()
            captureSession.close()
            session = null
            camera.close()
            device = null
            delay(300) // Let in-flight frames reach the GPU and the encoder.
            withContext(glDispatcher) { pipeline.release() }
            gl = null
            withContext(Dispatchers.IO) { enc.stop() }
            encoder = null

            val error = cameraLost.get() ?: pipeline.error ?: enc.error
            return summarise(withPlan, config, pipeline, enc, sensorLog, failures.get(), lost.get(), seconds, thermal.toList(), file, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(withPlan, e, cameraLost.get(), thermal.toList())
        } finally {
            // NonCancellable: a cancelled run (screen left, view model cleared) must still free the camera, EGL and codec.
            withContext(NonCancellable) {
                runCatching { session?.close() }
                runCatching { device?.close() }
                gl?.let { p -> runCatching { withContext(glDispatcher) { p.release() } } }
                encoder?.let { e -> runCatching { withContext(Dispatchers.IO) { e.stop() } } }
                glThread.quitSafely()
                cameraThread.quitSafely()
            }
        }
    }

    /** A lost camera makes later calls throw "already closed": report why it was lost, not the follow-up error. */
    private fun failed(base: PipelineResult, e: Exception, cameraLost: String?, thermal: List<ThermalSample>) = base.copy(
        error = cameraLost?.let { "$it (then ${e.javaClass.simpleName})" } ?: "${e.javaClass.simpleName}: ${e.message}",
        sessionRejected = e is SessionRejectedException,
        thermal = thermal
    )

    /** What the capture callback counts, only while the measurement window is open. */
    private class CaptureCounters(val sensorLog: TimestampLog, val failures: AtomicInteger, val lost: AtomicInteger, val measuring: AtomicBoolean)

    @Suppress("LongParameterList")
    private suspend fun startCapture(
        camera: CameraDevice,
        target: Surface,
        plan: Plan,
        config: PipelineConfig,
        executor: Executor,
        handler: Handler,
        counters: CaptureCounters
    ): CameraCaptureSession {
        val output = OutputConfiguration(target).apply { if (config.tenBit) dynamicRangeProfile = plan.profile }
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(target)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.fpsRange)
            // Stabilisation off: it changes the ISP load and crop, and is measured on its own later (REQ-CAM-003).
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }.build()
        // The same request as session parameters, so the HAL configures a sensor mode for the target frame rate.
        val session = camera.createSession(listOf(output), executor, sessionParameters = request)
        session.setRepeatingRequest(
            request,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    if (counters.measuring.get()) result.get(CaptureResult.SENSOR_TIMESTAMP)?.let(counters.sensorLog::add)
                }

                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    if (counters.measuring.get()) counters.failures.incrementAndGet()
                }

                override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: Surface, frameNumber: Long) {
                    if (counters.measuring.get()) counters.lost.incrementAndGet()
                }
            },
            handler
        )
        return session
    }

    @Suppress("LongParameterList")
    private fun summarise(
        base: PipelineResult,
        config: PipelineConfig,
        pipeline: GlPipeline,
        enc: SurfaceVideoEncoder,
        sensorLog: TimestampLog,
        failures: Int,
        lost: Int,
        seconds: Double,
        thermal: List<ThermalSample>,
        file: File,
        error: String?
    ): PipelineResult {
        val sensor = sensorLog.stats(config.fps)
        val gpu = pipeline.timestamps.stats(config.fps)
        val frames = enc.encodedFrames.get()
        val encodedFps = if (frames > 1) (frames - 1) * 1_000_000.0 / (enc.lastPtsUs.get() - enc.firstPtsUs.get()) else 0.0
        val recordedSeconds = pipeline.framesTotal.coerceAtLeast(1) / config.fps.toDouble()
        return base.copy(
            eglRecordable = pipeline.eglRecordable,
            eglHlgColorspace = pipeline.hlgTagged,
            previewError = pipeline.previewError,
            proxyError = pipeline.proxyError,
            measuredSeconds = seconds,
            sensor = sensor,
            gpu = gpu,
            gpuFramesTotal = pipeline.framesTotal,
            encodedFramesTotal = frames,
            encodedFps = encodedFps,
            captureFailures = failures,
            buffersLost = lost,
            renderCpu = DurationStats.of(pipeline.renderTimes()),
            encodedMbps = enc.encodedBytes.get() * 8 / 1_000_000.0 / recordedSeconds,
            thermal = thermal,
            outputFile = file.absolutePath,
            outputFormat = enc.outputFormat,
            meetsTarget = error == null && sensor.dropped == 0 && gpu.dropped == 0 &&
                gpu.fps >= config.fps * FPS_TOLERANCE && frames >= pipeline.framesTotal,
            error = error
        )
    }

    private class Plan(val fpsRange: Range<Int>, val profile: Long, val profileName: String)

    private class Unsupported(reason: String) : Exception(reason)

    /** Checks that the camera can deliver the size, the frame rate and (if asked) a 10-bit profile, or throws [Unsupported]. */
    private fun plan(config: PipelineConfig, chars: CameraCharacteristics): Plan {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = Size(config.width, config.height)
        if (map == null || map.getOutputSizes(SurfaceTexture::class.java)?.contains(size) != true) throw Unsupported("camera has no $size GPU output")
        val minFrameNs = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        if (minFrameNs > 0 && minFrameNs > 1_000_000_000.0 / config.fps * 1.01) {
            throw Unsupported("$size is limited to ${(1_000_000_000.0 / minFrameNs).toInt()} fps")
        }
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val range = ranges.firstOrNull { it.lower == config.fps && it.upper == config.fps } ?: ranges.firstOrNull { it.upper == config.fps }
            ?: throw Unsupported("no AE target range reaching ${config.fps} fps (${ranges.joinToString()})")
        if (!config.tenBit) return Plan(range, DynamicRangeProfiles.STANDARD, "STANDARD")
        val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty()
        val profile = TEN_BIT_PREFERENCE.firstOrNull { it in profiles }
            ?: throw Unsupported("no 10-bit dynamic range profile (has ${profiles.map(CameraNames::dynamicRangeProfile)})")
        return Plan(range, profile, CameraNames.dynamicRangeProfile(profile))
    }

    private fun mainBackCamera(): String? = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    private companion object {
        const val FPS_TOLERANCE = 0.98
        val TEN_BIT_PREFERENCE = listOf(DynamicRangeProfiles.HLG10, DynamicRangeProfiles.HDR10, DynamicRangeProfiles.HDR10_PLUS)
    }
}
