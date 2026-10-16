package com.qtekfun.ultimatecamera.core.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.util.Size
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class ConcurrentSetResult(
    val cameraIds: List<String>,
    /** What `isConcurrentSessionConfigurationSupported` says for one 1280x720 GPU stream per camera. */
    val declaredSupported: Boolean?,
    /** Measured frame rate per camera while all of them streamed at once; empty when the open test was skipped or failed. */
    val measuredFps: Map<String, Double>,
    val error: String? = null
)

@Serializable
data class ConcurrentCameraReport(val sets: List<ConcurrentSetResult>)

/** REQ-NET-001 groundwork: which camera combinations can stream at the same time on this phone. Needs CAMERA. */
class ConcurrentCameraProbe(private val manager: CameraManager, private val handler: Handler, private val executor: Executor) {
    private val size = Size(1280, 720)

    suspend fun run(streamSeconds: Int = 5): ConcurrentCameraReport {
        val sets = manager.concurrentCameraIds.map { it.sorted() }.sortedBy { it.joinToString() }
        return ConcurrentCameraReport(sets.map { ids -> probe(ids, streamSeconds) })
    }

    // Probe boundary: any vendor failure is a result, not a crash. CAMERA is checked by the caller.
    @Suppress("TooGenericExceptionCaught")
    @SuppressLint("MissingPermission")
    private suspend fun probe(ids: List<String>, streamSeconds: Int): ConcurrentSetResult {
        val declared = runCatching {
            manager.isConcurrentSessionConfigurationSupported(
                ids.associateWith {
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(size, SurfaceTexture::class.java)),
                        executor,
                        NoopSessionCallback
                    )
                }
            )
        }.getOrNull()
        if (declared == false) return ConcurrentSetResult(ids, declared, emptyMap())

        val devices = mutableListOf<CameraDevice>()
        val sessions = mutableListOf<CameraCaptureSession>()
        val readers = mutableListOf<ImageReader>()
        return try {
            val counters = ids.associateWith { AtomicInteger() }
            for (id in ids) {
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 4).also(readers::add)
                reader.setOnImageAvailableListener({ r ->
                    r.acquireLatestImage()?.close()
                    counters.getValue(id).incrementAndGet()
                }, handler)
                val device = manager.openCameraDevice(id, handler).also(devices::add)
                val session = device.createSession(listOf(OutputConfiguration(reader.surface)), executor).also(sessions::add)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }.build()
                session.setRepeatingRequest(request, null, handler)
            }
            delay(1_000) // Let auto-exposure settle; the first frames are often slow.
            counters.values.forEach { it.set(0) }
            val start = SystemClock.elapsedRealtime()
            delay(streamSeconds * 1_000L)
            val seconds = (SystemClock.elapsedRealtime() - start) / 1_000.0
            ConcurrentSetResult(ids, declared, counters.mapValues { (_, n) -> n.get() / seconds })
        } catch (e: Exception) {
            ConcurrentSetResult(ids, declared, emptyMap(), error = "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            sessions.forEach { runCatching { it.close() } }
            devices.forEach { runCatching { it.close() } }
            readers.forEach { runCatching { it.close() } }
        }
    }

    private object NoopSessionCallback : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = Unit
        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
    }
}
