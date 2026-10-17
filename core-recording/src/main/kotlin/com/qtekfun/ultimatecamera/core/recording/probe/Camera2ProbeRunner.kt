package com.qtekfun.ultimatecamera.core.recording.probe

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import com.qtekfun.ultimatecamera.core.camera.CameraCapabilityProbe
import com.qtekfun.ultimatecamera.core.camera.SessionRejectedException
import com.qtekfun.ultimatecamera.core.capabilities.AdvertisedDevice
import com.qtekfun.ultimatecamera.core.capabilities.AndroidCapabilities
import com.qtekfun.ultimatecamera.core.capabilities.DirectMode
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
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The real [ProbeRunner] (D42): Camera2 session with session parameters ([com.qtekfun.ultimatecamera.core.camera.CameraStream],
 * plus the vendor keys the camera lists) → CameraRenderer with an identity 33³ LUT per
 * [com.qtekfun.ultimatecamera.core.capabilities.LutMode] → hardware SurfaceVideoEncoder into a temporary MP4 under
 * `cacheDir/probe`, deleted afterwards. The encoder runs from the start (its own warm-up is part of the warm-up);
 * frames and camera timestamps are sampled at both ends of the measured window ([FrameWindow]). The spike's
 * `PipelineTest` is the reference; unlike it, nothing is reported or kept.
 */
class Camera2ProbeRunner(context: Context, private val thermal: ThermalMonitor = AndroidCapabilities.thermalMonitor(context)) : ProbeRunner {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val tempDir = File(context.cacheDir, TEMP_DIR)
    private val encoders: List<EncoderInfo> by lazy { CodecCapabilityProbe.encoders().filter { it.hardware } }

    @Suppress("TooGenericExceptionCaught") // Vendor HALs, EGL and codecs throw anything; each failure maps to a status.
    override suspend fun run(request: ProbeRequest): ProbeMeasurement = withContext(Dispatchers.Default) {
        val setup = when (val prepared = prepare(request)) {
            is Prepared.Run -> prepared.setup
            is Prepared.Done -> return@withContext prepared.measurement
        }
        val session = ProbeSession(manager, thermal, tempDir, setup)
        try {
            session.measure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(statusOf(e), e, session.thermalMax)
        } finally {
            withContext(NonCancellable) { session.release() }
        }
    }

    /** What can be decided without opening anything: a [ProbeSetup] to run, or the [ProbeMeasurement] that ends it. */
    private fun prepare(request: ProbeRequest): Prepared {
        val config = request.config
        val encoderConfig = runCatching { ProbeMapping.encoderConfig(config) }.getOrNull()
            ?: return done(SessionStatus.ENCODER_UNSUPPORTED, "invalid encoder configuration")
        val encoderInfo = CodecCapabilityProbe.pick(encoders, encoderConfig)
            ?: return done(SessionStatus.ENCODER_UNSUPPORTED, "no hardware encoder for ${encoderConfig.label}")
        // CameraAccessException or an unknown id (IllegalArgumentException): a camera error, not a verdict.
        val chars = runCatching { manager.getCameraCharacteristics(request.cameraId) }
            .getOrElse { return Prepared.Done(failure(SessionStatus.CAMERA_ERROR, it)) }
        val profile = ProbeMapping.dynamicRangeProfile(config.dynamicRange)
        val listed = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles.orEmpty()
        if (profile != null && profile !in listed) return done(SessionStatus.SESSION_REJECTED, "${config.dynamicRange} not listed")
        val keys = chars.availableCaptureRequestKeys.map { it.name }.toSet()
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val vendor = request.vendorParameters.filter { it.key in keys }
        // A vendor variant whose keys the camera does not list would run as the plain session and "hold" for nothing.
        val unlisted = config.vendorMode != null && !DirectMode.isDirect(config) && vendor.isEmpty() && request.sessionType == null
        return if (unlisted) {
            done(SessionStatus.SESSION_REJECTED, "${config.vendorMode}: no listed vendor key")
        } else {
            Prepared.Run(ProbeSetup(request, encoderConfig, encoderInfo.name, profile, vendor, sensor))
        }
    }

    private sealed interface Prepared {
        data class Run(val setup: ProbeSetup) : Prepared

        data class Done(val measurement: ProbeMeasurement) : Prepared
    }

    private fun done(status: SessionStatus, error: String) = Prepared.Done(ProbeMeasurement(status, error = error))

    private fun statusOf(e: Exception): SessionStatus = when (e) {
        is SessionRejectedException -> SessionStatus.SESSION_REJECTED
        is EncoderRefusedException -> SessionStatus.ENCODER_UNSUPPORTED
        // Camera lost or busy, GL context creation, a vendor HAL's IllegalArgumentException: not a capability verdict.
        else -> SessionStatus.CAMERA_ERROR
    }

    private fun failure(status: SessionStatus, e: Throwable, thermalMax: Int = ThermalStatus.NONE) =
        ProbeMeasurement(status, thermalStatusMax = thermalMax, error = "${e.javaClass.simpleName}: ${e.message}")

    /** Removes temporary files a killed probe left behind (call before a run). */
    fun cleanUp() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val TEMP_DIR = "probe"
    }
}

/** Sets a vendor key from its textual value; a value that does not parse is skipped (the plain session still runs). */
internal fun CaptureRequest.Builder.setVendor(p: VendorParameter) {
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
