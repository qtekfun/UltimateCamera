package com.qtekfun.ultimatecamera.feature.spike

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qtekfun.ultimatecamera.core.audio.AudioInputKind
import com.qtekfun.ultimatecamera.core.audio.AudioInputProbe
import com.qtekfun.ultimatecamera.core.camera.CameraCapabilityProbe
import com.qtekfun.ultimatecamera.core.camera.ConcurrentCameraProbe
import com.qtekfun.ultimatecamera.core.codec.CodecCapabilityProbe
import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.CubeLutParser
import com.qtekfun.ultimatecamera.core.gpu.SpikeLuts
import com.qtekfun.ultimatecamera.core.gpu.gl.GpuInfoProbe
import com.qtekfun.ultimatecamera.core.net.UdpLatencyProbe
import com.qtekfun.ultimatecamera.core.storage.DownloadsExporter
import com.qtekfun.ultimatecamera.feature.spike.pipeline.PipelineConfig
import com.qtekfun.ultimatecamera.feature.spike.pipeline.PipelineTest
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One spike test the user (or the adb automation) can start. */
enum class SpikeTest(val key: String) {
    CAPABILITIES("caps"),
    MATRIX("matrix"),
    SUSTAINED("sustained"),
    CONCURRENT("concurrent"),
    AUDIO("audio"),
    NETWORK("net")
    ;

    companion object {
        fun parse(list: String): List<SpikeTest> = list.split(',').map(String::trim).mapNotNull { k -> entries.firstOrNull { it.key == k } }
    }
}

data class SpikeUiState(
    val running: Boolean = false,
    val status: String = "",
    val report: SpikeReport,
    val lutName: String,
    val echoServerRunning: Boolean = false,
    val localAddresses: List<String> = emptyList(),
    val lastExport: String? = null,
    /** REQ-MON-003: full screen brightness on demand; normal brightness by default. */
    val maxBrightness: Boolean = false
)

/** Runs the Phase 0 tests one at a time and keeps the combined [SpikeReport]. */
class SpikeViewModel(application: Application, private val appVersion: String) : AndroidViewModel(application) {
    private val context = application
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val outputDir = File(context.getExternalFilesDir(null), "spike").apply { mkdirs() }
    private var lut: CubeLut = SpikeLuts.creative(33)

    /** The report lives on disk, rewritten after every result, so a crash or a killed app loses nothing. */
    private val reportFile = File(outputDir, "report-current.json")
    private val diskWriter = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(
        SpikeUiState(report = restoreReport() ?: emptyReport(), lutName = lut.title.orEmpty(), localAddresses = UdpLatencyProbe.localAddresses())
    )
    val state: StateFlow<SpikeUiState> = _state.asStateFlow()

    /** Set by the preview SurfaceView; read when a pipeline run starts. */
    @Volatile var previewSurface: Surface? = null

    private var echoJob: Job? = null

    fun run(tests: List<SpikeTest>, seconds: Int = 20, sustainedSeconds: Int = 300, netHost: String? = null, export: Boolean = false) {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true) }
            for (test in tests) {
                runCatching { runOne(test, seconds, sustainedSeconds, netHost) }.onFailure { e ->
                    record { it.copy(errors = it.errors + "${test.key}: ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
            if (export) exportReport()
            status(R.string.spike_status_done)
            Log.i(TAG, "DONE ${_state.value.lastExport ?: "-"}")
            _state.update { it.copy(running = false) }
        }
    }

    private suspend fun runOne(test: SpikeTest, seconds: Int, sustainedSeconds: Int, netHost: String?) {
        when (test) {
            SpikeTest.CAPABILITIES -> capabilities()
            SpikeTest.MATRIX -> {
                val encoders = ensureEncoders()
                val matrix = SpikeMatrix.default(encoders)
                matrix.forEachIndexed { i, config ->
                    status(R.string.spike_status_matrix, i + 1, matrix.size, config.label)
                    pipeline(config, seconds)
                    delay(COOLDOWN_MS)
                }
            }
            SpikeTest.SUSTAINED -> pipeline(SpikeMatrix.sustained, sustainedSeconds)
            SpikeTest.CONCURRENT -> {
                require(granted(Manifest.permission.CAMERA)) { "camera permission not granted" }
                status(R.string.spike_run_concurrent)
                val thread = HandlerThread("uc-concurrent").apply { start() }
                try {
                    val handler = Handler(thread.looper)
                    val report = ConcurrentCameraProbe(cameraManager, handler) { handler.post(it) }.run()
                    record { it.copy(concurrent = report) }
                } finally {
                    thread.quitSafely()
                }
            }
            SpikeTest.AUDIO -> audio()
            SpikeTest.NETWORK -> {
                val host = netHost ?: error("no echo host given")
                status(R.string.spike_status_network, host)
                val result = UdpLatencyProbe.measure(host)
                record { it.copy(network = it.network + result) }
            }
        }
    }

    private suspend fun capabilities() {
        status(R.string.spike_run_caps)
        val gpu = withContext(Dispatchers.Default) { GpuInfoProbe.probe() }
        val cameras = withContext(Dispatchers.Default) { CameraCapabilityProbe(cameraManager).cameras() }
        val encoders = withContext(Dispatchers.Default) { CodecCapabilityProbe.encoders() }
        val inputs = AudioInputProbe(context).inputs()
        record { it.copy(gpu = gpu, cameras = cameras, encoders = encoders, audioInputs = inputs) }
    }

    private suspend fun ensureEncoders() = _state.value.report.encoders
        ?: withContext(Dispatchers.Default) { CodecCapabilityProbe.encoders() }.also { e -> record { it.copy(encoders = e) } }

    private suspend fun pipeline(config: PipelineConfig, seconds: Int) {
        require(granted(Manifest.permission.CAMERA)) { "camera permission not granted" }
        val result = PipelineTest(context, ensureEncoders(), outputDir).run(
            config = config,
            lut = lut,
            previewSurface = previewSurface,
            maxBrightness = _state.value.maxBrightness,
            durationSeconds = seconds,
            onProgress = ::status
        )
        Log.i(TAG, "PIPELINE ${result.label} meets=${result.meetsTarget} gpuFps=${result.gpu?.fps} drops=${result.gpu?.dropped}")
        if (result.reason != null || result.error != null) Log.i(TAG, "PIPELINE ${result.label} reason=${result.reason} error=${result.error}")
        record { it.copy(pipeline = it.pipeline + result) }
    }

    private suspend fun audio() {
        require(granted(Manifest.permission.RECORD_AUDIO)) { "microphone permission not granted" }
        val probe = AudioInputProbe(context)
        record { it.copy(audioInputs = probe.inputs()) }
        for (device in probe.devices()) {
            status(R.string.spike_status_audio, device.productName, AudioInputKind.typeName(device.type))
            val result = probe.record(device, AUDIO_SECONDS, outputDir)
            record { it.copy(audioRecordings = it.audioRecordings + result) }
        }
    }

    fun setMaxBrightness(enabled: Boolean) = _state.update { it.copy(maxBrightness = enabled) }

    fun toggleEchoServer() {
        echoJob?.let {
            it.cancel()
            echoJob = null
            _state.update { s -> s.copy(echoServerRunning = false) }
            return
        }
        echoJob = viewModelScope.launch { UdpLatencyProbe.serve() }
        _state.update { it.copy(echoServerRunning = true, localAddresses = UdpLatencyProbe.localAddresses()) }
    }

    fun importLut(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("cannot read $uri")
                    CubeLutParser.parse(text)
                }
            }.onSuccess { parsed ->
                lut = parsed
                _state.update { it.copy(lutName = "${parsed.title ?: uri.lastPathSegment} (${parsed.size}³)") }
            }.onFailure { e -> status(R.string.spike_status_lut_failed, e.message.orEmpty()) }
        }
    }

    fun export() {
        viewModelScope.launch { exportReport() }
    }

    private suspend fun exportReport() {
        val report = _state.value.report
        val name = "spike-${report.device.manufacturer}-${report.device.model}-${LocalDateTime.now().format(FILE_TIME)}.json".replace(' ', '_')
        runCatching {
            withContext(Dispatchers.IO) { DownloadsExporter(context, EXPORT_FOLDER).write(name, "application/json", report.toJson()) }
        }.onSuccess {
            _state.update { s -> s.copy(lastExport = "Download/$EXPORT_FOLDER/$name") }
            Log.i(TAG, "EXPORTED Download/$EXPORT_FOLDER/$name")
        }.onFailure { e -> status(R.string.spike_status_export_failed, e.message.orEmpty()) }
    }

    fun reset() {
        if (_state.value.running) return
        _state.update { it.copy(report = emptyReport(), lastExport = null) }
        viewModelScope.launch(diskWriter) { reportFile.delete() }
    }

    private fun record(change: (SpikeReport) -> SpikeReport) {
        val report = _state.updateAndGet { it.copy(report = change(it.report)) }.report
        viewModelScope.launch(diskWriter) {
            val tmp = File(reportFile.path + ".tmp")
            tmp.writeText(report.toJson())
            if (!tmp.renameTo(reportFile)) Log.w(TAG, "could not replace $reportFile")
        }
    }

    private fun restoreReport(): SpikeReport? = runCatching {
        SpikeReport.JSON.decodeFromString(SpikeReport.serializer(), reportFile.readText())
    }.getOrNull()

    private fun status(text: String) = _state.update { it.copy(status = text) }

    private fun status(@StringRes id: Int, vararg args: Any) = status(context.getString(id, *args))

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun emptyReport() = SpikeReport(appVersion = appVersion, createdAt = LocalDateTime.now().toString(), device = DeviceInfo.current())

    companion object {
        const val TAG = "UCSPIKE"
        const val EXPORT_FOLDER = "UltimateCamera-spike"
        private const val COOLDOWN_MS = 5_000L
        private const val AUDIO_SECONDS = 4
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
