package com.qtekfun.ultimatecamera.core.camera

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * A photo session through a Camera2 extension (D64: the maker's own HDR, Auto or Night processing): the preview goes
 * into [previewTarget] (the GPU stage, sized from the extension's SurfaceTexture sizes) and the still into a JPEG (or
 * JPEG_R) [ImageReader]. The extension runs its own 3A and multi-frame processing, so [capture] sends one request.
 */
class ExtensionStillStream private constructor(
    private val device: CameraDevice,
    private val session: CameraExtensionSession,
    private val reader: ImageReader,
    private val previewTarget: Surface,
    handler: Handler
) : StillSource {
    private val executor = Executor { handler.post(it) }
    private val images = Channel<Image>(Channel.UNLIMITED)
    private val callback = object : CameraExtensionSession.ExtensionCaptureCallback() {}

    init {
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { if (images.trySend(it).isFailure) it.close() } }, handler)
    }

    private fun startPreview() {
        val preview = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewTarget) }.build()
        session.setRepeatingRequest(preview, executor, callback)
    }

    override suspend fun <T> capture(jpegOrientation: Int, quality: Int, consume: (ByteBuffer) -> T): T {
        while (true) images.tryReceive().getOrNull()?.close() ?: break
        val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            set(CaptureRequest.JPEG_QUALITY, quality.toByte())
        }.build()
        session.capture(still, executor, callback)
        // Multi-frame processing (night) takes seconds on modest phones.
        val image = withTimeout(EXTENSION_TIMEOUT_MS) { images.receive() }
        return image.use { consume(it.planes[0].buffer) }
    }

    override fun close() {
        runCatching { session.stopRepeating() }
        runCatching { session.close() }
        device.close()
        reader.close()
        while (true) images.tryReceive().getOrNull()?.close() ?: break
        images.close()
    }

    companion object {
        const val EXTENSION_TIMEOUT_MS = 15_000L
        private const val MAX_IMAGES = 2

        /**
         * Opens [cameraId] with [extension] (a `CameraExtensionCharacteristics.EXTENSION_*`): preview into
         * [previewTarget], still of [format] at [still]. Throws [SessionRejectedException] when the extension refuses.
         */
        @Suppress("LongParameterList")
        suspend fun open(
            manager: CameraManager,
            cameraId: String,
            extension: Int,
            previewTarget: Surface,
            still: PixelSize,
            format: Int,
            handler: Handler,
            onLost: (String) -> Unit
        ): ExtensionStillStream {
            val reader = ImageReader.newInstance(still.width, still.height, format, MAX_IMAGES)
            val executor = Executor { handler.post(it) }
            var device: CameraDevice? = null
            var opened = false
            try {
                device = manager.openCameraDevice(cameraId, handler, onLost)
                val session = device.createExtensionSession(
                    extension,
                    listOf(OutputConfiguration(previewTarget), OutputConfiguration(reader.surface)),
                    executor
                )
                val stream = ExtensionStillStream(device, session, reader, previewTarget, handler)
                stream.startPreview()
                opened = true
                return stream
            } finally {
                if (!opened) {
                    device?.close()
                    reader.close()
                }
            }
        }

        private suspend fun CameraDevice.createExtensionSession(
            extension: Int,
            outputs: List<OutputConfiguration>,
            executor: Executor
        ): CameraExtensionSession = suspendCancellableCoroutine { cont ->
            val config = ExtensionSessionConfiguration(
                extension,
                outputs,
                executor,
                object : CameraExtensionSession.StateCallback() {
                    override fun onConfigured(session: CameraExtensionSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraExtensionSession) {
                        if (cont.isActive) cont.resumeWithException(SessionRejectedException("extension session configuration failed"))
                    }
                }
            )
            createExtensionSession(config)
        }
    }
}

/** A photo session that takes one encoded still (JPEG or JPEG_R) per [capture]. */
interface StillSource {
    /** Takes one still rotated by [jpegOrientation] and hands its bytes to [consume] (valid only during the call). */
    suspend fun <T> capture(jpegOrientation: Int, quality: Int = StillCaptureStream.DEFAULT_QUALITY, consume: (ByteBuffer) -> T): T

    fun close()
}
