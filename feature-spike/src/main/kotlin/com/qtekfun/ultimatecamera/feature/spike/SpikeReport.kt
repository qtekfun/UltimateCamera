package com.qtekfun.ultimatecamera.feature.spike

import android.os.Build
import com.qtekfun.ultimatecamera.core.audio.AudioInput
import com.qtekfun.ultimatecamera.core.audio.AudioRecordingResult
import com.qtekfun.ultimatecamera.core.camera.CameraInfo
import com.qtekfun.ultimatecamera.core.camera.ConcurrentCameraReport
import com.qtekfun.ultimatecamera.core.codec.EncoderInfo
import com.qtekfun.ultimatecamera.core.gpu.gl.GpuInfo
import com.qtekfun.ultimatecamera.core.net.NetworkLatencyResult
import com.qtekfun.ultimatecamera.feature.spike.pipeline.PipelineResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val socManufacturer: String,
    val socModel: String,
    val android: String,
    val sdk: Int,
    val build: String
) {
    companion object {
        fun current() = DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            socManufacturer = Build.SOC_MANUFACTURER,
            socModel = Build.SOC_MODEL,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            build = Build.DISPLAY
        )
    }
}

/** Everything one phone reported in Phase 0. Exported as JSON for docs/spike-report.md and the capability catalogue. */
@Serializable
data class SpikeReport(
    val schema: Int = 1,
    val appVersion: String,
    val createdAt: String,
    val device: DeviceInfo,
    val gpu: GpuInfo? = null,
    val cameras: List<CameraInfo>? = null,
    val encoders: List<EncoderInfo>? = null,
    val pipeline: List<PipelineResult> = emptyList(),
    val concurrent: ConcurrentCameraReport? = null,
    val audioInputs: List<AudioInput>? = null,
    val audioRecordings: List<AudioRecordingResult> = emptyList(),
    val network: List<NetworkLatencyResult> = emptyList(),
    val errors: List<String> = emptyList()
) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            allowSpecialFloatingPointValues = true
            ignoreUnknownKeys = true
        }
    }
}
