package com.qtekfun.ultimatecamera.core.recording.probe

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import com.qtekfun.ultimatecamera.core.camera.CameraCapabilityProbe
import com.qtekfun.ultimatecamera.core.camera.CameraLostException
import com.qtekfun.ultimatecamera.core.camera.CameraStream
import com.qtekfun.ultimatecamera.core.camera.SessionRejectedException
import com.qtekfun.ultimatecamera.core.camera.StreamSpec
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedDevice
import com.qtekfun.ultimatecamera.core.capabilities.AndroidCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.ProbeMeasurement
import com.qtekfun.ultimatecamera.core.capabilities.ProbeRequest
import com.qtekfun.ultimatecamera.core.capabilities.ProbeRunner
import com.qtekfun.ultimatecamera.core.capabilities.SessionStatus
import com.qtekfun.ultimatecamera.core.capabilities.ThermalMonitor
import com.qtekfun.ultimatecamera.core.capabilities.ThermalStatus
import com.qtekfun.ultimatecamera.core.capabilities.VendorParameter
import com.qtekfun.ultimatecamera.core.capabilities.VendorValueType
import com.qtekfun.ultimatecamera.core.codec.CodecCapabilityProbe
import com.qtekfun.ultimatecamera.core.codec.EncoderInfo
import com.qtekfun.ultimatecamera.core.codec.SurfaceVideoEncoder
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.LutInterpolation
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The real [ProbeRunner] (D42): Camera2 session with session parameters ([CameraStream]) → [CameraRenderer] with an
 * identity 33³ LUT per [com.qtekfun.ultimatecamera.core.capabilities.LutMode] → [SurfaceVideoEncoder] into a temporary
 * MP4 under `cacheDir/probe` that is deleted afterwards. The encoder runs from the start (its own warm-up is part of
 * the warm-up); frames and camera timestamps are sampled at the end of the warm-up and of the measured window
 * ([FrameWindow]). The spike's `PipelineTest` is the reference; unlike it, nothing is reported or kept.
 */
class Camera2ProbeRunner(
    context: Context,
    private val thermal: ThermalMonitor = AndroidCapabilities.thermalMonitor(context)
) : ProbeRunner {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val tempDir = File(context.cacheDir, TEMP_DIR)
    private val encoders: List<EncoderInfo> by lazy { CodecCapabilityProbe.encoders() }

    @Suppress("TooGenericExceptionCaught", "LongMethod") // Vendor HALs and codecs throw anything; each maps to a status.
    override suspend fun run(request: ProbeRequest): ProbeMeasurement = withContext(Dispatchers.Default) {
        val config = request.config
        val encoderConfig = runCatching { ProbeMapping.encoderConfig(config) }.getOrNull()
            ?: return@withContext ProbeMeasurement(SessionStatus.ENCODER_UNSUPPORTED, error = "invalid encoder configuration")
        val encoderInfo = CodecCapabilityProbe.pick(encoders.filter { it.hardware }, encoderConfig)
            ?: return@withContext ProbeMeasurement(SessionStatus.ENCODER_UNSUPPORTED, error = "no hardware encoder for ${encoderConfig.label}")
        val chars = try {
            manager.getCameraCharacteristics(request.cameraId)
        } catch (e: CameraAccessException) {
            return@withContext failure(SessionStatus.CAMERA_ERROR, e)
        } catch (e: IllegalArgumentException) {
            return@withContext failure(SessionStatus.CAMERA_ERROR, e)
        }
        val profile = ProbeMapping.dynamicRangeProfile(config.dynamicRange)
        if (profile != null) {
            val listed = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty()
            if (profile !in listed) return@withContext ProbeMeasurement(SessionStatus.SESSION_REJECTED, error = "${config.dynamicRange} not listed")
        }

        val thread = HandlerThread("uc-probe").apply { start() }
        var renderer: CameraRenderer? = null
        var encoder: SurfaceVideoEncoder? = null
        var stream: CameraStream? = null
        var file: File? = null
        var thermalMax = thermal.status()
        val lostRef = AtomicReference<String?>(null)
        try {
            val r = CameraRenderer(
                depth = if (config.tenBit) EglColorDepth.RGBA1010102 else EglColorDepth.RGBA8888,
                width = config.width,
                height = config.height,
                hlg = config.tenBit,
                interpolation = ProbeMapping.interpolation(config.lut) ?: LutInterpolation.HARDWARE
            ).also { renderer = it }
            if (ProbeMapping.interpolation(config.lut) != null) r.setLut(CubeLut.identity(LUT_SIZE))
            val out = withContext(Dispatchers.IO) {
                tempDir.mkdirs()
                File.createTempFile("probe", ".mp4", tempDir)
            }.also { file = it }
            val enc = try {
                SurfaceVideoEncoder(encoderConfig, encoderInfo.name, out)
            } catch (e: MediaCodec.CodecException) {
                return@withContext failure(SessionStatus.ENCODER_UNSUPPORTED, e)
            } catch (e: IllegalArgumentException) {
                return@withContext failure(SessionStatus.ENCODER_UNSUPPORTED, e)
            } catch (e: IllegalStateException) {
                return@withContext failure(SessionStatus.ENCODER_UNSUPPORTED, e)
            }.also { encoder = it }
            val keys = chars.availableCaptureRequestKeys.map { it.name }.toSet()
            val vendor = request.vendorParameters.filter { it.key in keys }
            val spec = StreamSpec(request.cameraId, config.width, config.height, config.fps, profile)
            r.startEncoding(enc.inputSurface, 0)
            stream = CameraStream.open(manager, spec, r.cameraSurface, Handler(thread.looper), extras = { b -> vendor.forEach { b.setVendor(it) } }) {
                lostRef.compareAndSet(null, it)
            }

            val warmupEnd = System.nanoTime() + request.warmupMs * NANOS_PER_MS
            while (System.nanoTime() < warmupEnd && lostRef.get() == null) {
                thermalMax = maxOf(thermalMax, thermal.status())
                delay(POLL_MS)
            }
            val startFrames = enc.encodedFrames.get()
            val startPts = enc.lastPtsUs.get()
            val measureEnd = System.nanoTime() + request.measureMs * NANOS_PER_MS
            while (System.nanoTime() < measureEnd && lostRef.get() == null && thermalMax < ThermalStatus.SEVERE) {
                delay(POLL_MS)
                thermalMax = maxOf(thermalMax, thermal.status())
            }
            val window = FrameWindow.of(startFrames, startPts, enc.encodedFrames.get(), enc.lastPtsUs.get(), config.fps)
            val lost = lostRef.get()
            when {
                lost != null -> ProbeMeasurement(SessionStatus.CAMERA_ERROR, thermalStatusMax = thermalMax, error = lost)
                enc.error != null && enc.encodedFrames.get() == 0 ->
                    ProbeMeasurement(SessionStatus.ENCODER_UNSUPPORTED, thermalStatusMax = thermalMax, error = enc.error)
                else -> ProbeMeasurement(SessionStatus.RAN, window.measuredFps, window.droppedFrames, thermalMax, r.error ?: enc.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionRejectedException) {
            failure(SessionStatus.SESSION_REJECTED, e, thermalMax)
        } catch (e: CameraLostException) {
            failure(SessionStatus.CAMERA_ERROR, e, thermalMax)
        } catch (e: CameraAccessException) {
            failure(SessionStatus.CAMERA_ERROR, e, thermalMax)
        } catch (e: IOException) {
            failure(SessionStatus.CAMERA_ERROR, e, thermalMax)
        } catch (e: RuntimeException) {
            // GL context creation, a vendor HAL's IllegalArgumentException or SecurityException: not a capability verdict.
            failure(SessionStatus.CAMERA_ERROR, e, thermalMax)
        } finally {
            withContext(NonCancellable) {
                runCatching { stream?.close() }
                runCatching { renderer?.stopEncoding() }
                runCatching { encoder?.stop() }
                runCatching { renderer?.release() }
                thread.quitSafely()
                withContext(Dispatchers.IO) { file?.delete() }
            }
        }
    }

    private fun failure(status: SessionStatus, e: Exception, thermalMax: Int = ThermalStatus.NONE) =
        ProbeMeasurement(status, thermalStatusMax = thermalMax, error = "${e.javaClass.simpleName}: ${e.message}")

    /** Removes temporary files a killed probe left behind (call before a run). */
    fun cleanUp() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val TEMP_DIR = "probe"
        const val LUT_SIZE = 33
        const val POLL_MS = 100L
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** Sets a vendor key from its textual value; a value that does not parse is skipped (the plain session still runs). */
private fun CaptureRequest.Builder.setVendor(p: VendorParameter) {
    runCatching {
        when (p.type) {
            VendorValueType.BYTE -> set(CaptureRequest.Key(p.key, Byte::class.javaObjectType), p.value.trim().toByte())
            VendorValueType.INT -> set(CaptureRequest.Key(p.key, Int::class.javaObjectType), p.value.trim().toInt())
            VendorValueType.LONG -> set(CaptureRequest.Key(p.key, Long::class.javaObjectType), p.value.trim().toLong())
            VendorValueType.FLOAT -> set(CaptureRequest.Key(p.key, Float::class.javaObjectType), p.value.trim().toFloat())
            VendorValueType.INT_ARRAY -> set(CaptureRequest.Key(p.key, IntArray::class.java), VendorValues.intArray(p.value))
        }
    }
}

/** Parsing of [VendorParameter.value] (pure, unit-tested). */
internal object VendorValues {
    fun intArray(value: String): IntArray = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
}

/** Reads what Camera2 and MediaCodec advertise, for the [com.qtekfun.ultimatecamera.core.capabilities.ProbePlanner]. */
object AdvertisedDeviceReader {
    /** The listed (logical) cameras and the hardware encoders; cameras that cannot be described are left out. */
    fun read(context: Context): AdvertisedDevice {
        val manager = context.getSystemService(CameraManager::class.java)
        val probe = CameraCapabilityProbe(manager)
        val cameras = manager.cameraIdList.mapNotNull { id -> runCatching { ProbeMapping.advertisedCamera(probe.describe(id)) }.getOrNull() }
        return AdvertisedDevice(AndroidCapabilities.identity(), cameras, ProbeMapping.advertisedEncoders(CodecCapabilityProbe.encoders()))
    }
}
