package com.qtekfun.ultimatecamera.feature.multicam.pairing

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** A [ScannerWorker] on an Android [HandlerThread]; [handler] also receives the image reader's frames. */
internal class HandlerWorker : ScannerWorker {
    private val thread = HandlerThread("qr-scanner").apply { start() }
    val handler = Handler(thread.looper)

    override fun post(block: () -> Unit): Boolean = handler.post(block)

    override fun quit() {
        thread.quitSafely()
    }
}

/**
 * A minimal Camera2 QR scanner for the pairing screen (no CameraX, docs/00-vision-y-decisiones.md): the back camera feeds a [TextureView]
 * preview and a small YUV [ImageReader]; each frame's Y plane goes to [QrCodes.decode] on a background thread (frames
 * arriving while one is decoded are dropped). [onText] is called once per distinct text, on the background thread.
 * Opened when the view's surface is ready, released when it goes away and opened again when it comes back (lifecycle and
 * failure handling in [ScannerCore]); closed for good by [close]. Needs the CAMERA permission (the app has it).
 */
class QrScanner(private val context: Context, private val onText: (String) -> Unit, private val onError: () -> Unit) : TextureView.SurfaceTextureListener {
    private val core = ScannerCore<HandlerWorker, CameraDevice, CameraCaptureSession>(
        newWorker = ::HandlerWorker,
        closeDevice = CameraDevice::close,
        closeSession = CameraCaptureSession::close,
        onError = { onError() }
    )
    private val executor = Executor { command -> core.post { command.run() } }
    private val busy = AtomicBoolean(false)
    private var lastText: String? = null

    /** Size chosen for preview and analysis (4:3); the screen sizes its box to it. */
    var size: Size = DEFAULT_SIZE
        private set

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = core.open { gen, worker -> open(gen, worker, surface) }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        core.stop()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    @SuppressLint("MissingPermission") // The screen only shows the scanner once CAMERA is granted.
    private fun open(gen: Int, worker: HandlerWorker, texture: SurfaceTexture) {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = runCatching { backCamera(manager) }.getOrNull() ?: return core.failed(gen)
        size = runCatching { pickSize(manager, id) }.getOrDefault(DEFAULT_SIZE)
        texture.setDefaultBufferSize(size.width, size.height)
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        core.attach(gen, imageReader)
        imageReader.setOnImageAvailableListener({ analyse(it) }, worker.handler)
        runCatching {
            manager.openCamera(
                id,
                executor,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (core.onOpened(gen, camera)) startSession(gen, camera, Surface(texture), imageReader, worker)
                    }

                    override fun onDisconnected(camera: CameraDevice) = core.onLost(camera)

                    override fun onError(camera: CameraDevice, error: Int) = core.onLost(camera)
                }
            )
        }.onFailure { core.failed(gen) }
    }

    private fun startSession(gen: Int, camera: CameraDevice, preview: Surface, imageReader: ImageReader, worker: HandlerWorker) {
        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(imageReader.surface))
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) = core.onConfigured(gen, camera, s) {
                // Throws IllegalStateException when the camera was closed meanwhile: ScannerCore makes it an error on screen.
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                s.setRepeatingRequest(request, null, worker.handler)
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                runCatching { s.close() }
                core.failed(gen)
            }
        }
        runCatching { camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)) }
            .onFailure { core.failed(gen) }
    }

    private fun analyse(imageReader: ImageReader) {
        val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return
        if (!busy.compareAndSet(false, true)) return image.close()
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val text = QrCodes.decode(bytes, image.width, image.height, plane.rowStride)
            if (text != null && text != lastText) {
                lastText = text
                onText(text)
            }
        } finally {
            image.close()
            busy.set(false)
        }
    }

    /** Lets the same code be reported again (e.g. after the user dismissed an error). */
    fun rearm() = core.post { lastText = null }

    /** For good (the screen left): camera released, thread quit. */
    fun close() = core.close()

    private fun backCamera(manager: CameraManager): String? = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: manager.cameraIdList.firstOrNull()

    private fun pickSize(manager: CameraManager, id: String): Size {
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return DEFAULT_SIZE
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        return sizes.filter { it.width * 3 == it.height * 4 && it.width <= MAX_WIDTH }.maxByOrNull { it.width } ?: DEFAULT_SIZE
    }

    private companion object {
        val DEFAULT_SIZE = Size(640, 480)
        const val MAX_WIDTH = 1280
    }
}
