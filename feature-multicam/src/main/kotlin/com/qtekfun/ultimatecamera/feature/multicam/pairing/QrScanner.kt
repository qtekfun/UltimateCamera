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

/**
 * A minimal Camera2 QR scanner for the pairing screen (no CameraX, CLAUDE.md): the back camera feeds a [TextureView]
 * preview and a small YUV [ImageReader]; each frame's Y plane goes to [QrCodes.decode] on a background thread (frames
 * arriving while one is decoded are dropped). [onText] is called once per distinct text, on the background thread.
 * Opened when the view's surface is ready, closed by [close]. Needs the CAMERA permission (the app has it).
 */
class QrScanner(private val context: Context, private val onText: (String) -> Unit, private val onError: () -> Unit) : TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("qr-scanner").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val busy = AtomicBoolean(false)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var lastText: String? = null
    private var closed = false

    /** Size chosen for preview and analysis (4:3); the screen sizes its box to it. */
    var size: Size = DEFAULT_SIZE
        private set

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = open(surface)

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        close()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    @SuppressLint("MissingPermission") // The screen only shows the scanner once CAMERA is granted.
    private fun open(texture: SurfaceTexture) {
        val manager = context.getSystemService(CameraManager::class.java)
        val id = runCatching { backCamera(manager) }.getOrNull() ?: return onError()
        size = runCatching { pickSize(manager, id) }.getOrDefault(DEFAULT_SIZE)
        texture.setDefaultBufferSize(size.width, size.height)
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { reader = it }
        imageReader.setOnImageAvailableListener({ analyse(it) }, handler)
        runCatching {
            manager.openCamera(
                id,
                executor,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (closed) return camera.close()
                        device = camera
                        startSession(camera, Surface(texture), imageReader)
                    }

                    override fun onDisconnected(camera: CameraDevice) = camera.close()

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        onError()
                    }
                }
            )
        }.onFailure { onError() }
    }

    private fun startSession(camera: CameraDevice, preview: Surface, imageReader: ImageReader) {
        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(imageReader.surface))
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) return s.close()
                session = s
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                runCatching { s.setRepeatingRequest(request, null, handler) }.onFailure { onError() }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) = onError()
        }
        runCatching { camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)) }
            .onFailure { onError() }
    }

    private fun analyse(imageReader: ImageReader) {
        val image = imageReader.acquireLatestImage() ?: return
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
    fun rearm() = handler.post { lastText = null }

    fun close() {
        handler.post {
            closed = true
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            session = null
            device = null
            reader = null
            thread.quitSafely()
        }
    }

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
