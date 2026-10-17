package com.qtekfun.ultimatecamera.core.recording

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.BracketCaptureStream
import com.qtekfun.ultimatecamera.core.camera.ExtensionStillStream
import com.qtekfun.ultimatecamera.core.camera.HdrChoice
import com.qtekfun.ultimatecamera.core.camera.HdrMethod
import com.qtekfun.ultimatecamera.core.camera.HdrPlan
import com.qtekfun.ultimatecamera.core.camera.HdrSelector
import com.qtekfun.ultimatecamera.core.camera.HdrSupport
import com.qtekfun.ultimatecamera.core.camera.PhotoHdrProbe
import com.qtekfun.ultimatecamera.core.camera.PhotoSizes
import com.qtekfun.ultimatecamera.core.camera.SessionRejectedException
import com.qtekfun.ultimatecamera.core.camera.StillCaptureStream
import com.qtekfun.ultimatecamera.core.camera.StillSource
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
import com.qtekfun.ultimatecamera.core.recording.PhotoFinisher.asStream
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import com.qtekfun.ultimatecamera.core.storage.OutputNaming
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the photo mode shows. */
sealed interface PhotoState {
    data object Closed : PhotoState

    /**
     * Open: [hdr] is what a shot will do and [hdrMethods] what this camera offers (D64); [processing] is true while our
     * own fusion merges the bracket after the shutter.
     */
    data class Ready(
        val sizes: PhotoSizes,
        val capturing: Boolean = false,
        val hdr: HdrPlan = HdrPlan(HdrMethod.OFF, ultraHdr = false),
        val hdrMethods: List<HdrMethod> = listOf(HdrMethod.OFF),
        val processing: Boolean = false
    ) : PhotoState

    data class Failed(val message: String) : PhotoState
}

/**
 * The photo mode (decisions D41, D64): the preview goes through the [CameraRenderer] in 8-bit SDR at a size with the
 * still's aspect, and the shutter takes one full-size photo written through core-storage. HDR is done the best way the
 * camera offers ([HdrSelector]): a Camera2 extension (the maker's processing), the camera's JPEG_R (Ultra HDR), or our
 * own bracket fusion ([HdrFusionPipeline]). Owns the camera while open: close the video [CameraController] first.
 * Call from one coroutine scope.
 */
class PhotoController(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("uc-photo").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val fusion = HdrFusionPipeline(File(context.cacheDir, "hdr"))

    private val _state = MutableStateFlow<PhotoState>(PhotoState.Closed)
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    private var still: StillSource? = null
    private var bracket: BracketCaptureStream? = null
    private var renderer: CameraRenderer? = null
    private var chars: CameraCharacteristics? = null
    private var preview: Surface? = null
    private var previewRotation = 0
    private val supportCache = HashMap<String, HdrSupport>()
    private var staleCleared = false

    /** The HDR choice the next [open] uses. */
    var hdrChoice: HdrChoice = HdrChoice.Auto

    /** Opens [cameraId] for photos with [hdrChoice] and starts the preview. Closes whatever was open. */
    @Suppress("TooGenericExceptionCaught") // Vendor HALs throw anything; the screen shows it instead of crashing.
    suspend fun open(cameraId: String) = withContext(Dispatchers.Default) {
        close()
        // Frames of a shot the app was killed in the middle of (≈54 MB at 12 MP).
        if (!staleCleared) staleCleared = runCatching { fusion.work.clear() }.isSuccess
        val support = hdrSupport(cameraId)
        val methods = HdrSelector.available(support)
        var plan = HdrSelector.plan(support, hdrChoice, gainMapEncoder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        try {
            val c = manager.getCameraCharacteristics(cameraId).also { chars = it }
            val sizes = try {
                openWith(cameraId, c, plan)
            } catch (e: Exception) {
                if (plan.method == HdrMethod.OFF) throw e
                // The HDR session was refused: plain JPEG rather than no camera (the chip then shows Off).
                release()
                plan = HdrPlan(HdrMethod.OFF, ultraHdr = false)
                openWith(cameraId, c, plan)
            }
            _state.value = PhotoState.Ready(sizes, hdr = plan, hdrMethods = methods)
        } catch (e: Exception) {
            release()
            _state.value = PhotoState.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** What [cameraId] offers for HDR; the extension service can block, so it is bounded and cached per camera. */
    private suspend fun hdrSupport(cameraId: String): HdrSupport = supportCache[cameraId] ?: run {
        // Detached: a blocked vendor service must not hold the camera screen; a late answer is dropped.
        val probe = CoroutineScope(Dispatchers.IO).async { PhotoHdrProbe.read(manager, cameraId) }
        (withTimeoutOrNull(HDR_PROBE_TIMEOUT_MS) { probe.await() } ?: HdrSupport().also { probe.cancel() }).also { supportCache[cameraId] = it }
    }

    /** Opens the session [plan] needs; returns its sizes. */
    private suspend fun openWith(cameraId: String, c: CameraCharacteristics, plan: HdrPlan): PhotoSizes {
        val method = plan.method
        val format = if (plan.ultraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ImageFormat.JPEG_R else ImageFormat.JPEG
        val sizes = when {
            method.extension -> extensionSizes(cameraId, method, format)
            method == HdrMethod.FUSION -> BracketCaptureStream.sizes(c)
            else -> StillCaptureStream.sizes(c, format)
        } ?: throw SessionRejectedException("no still output for ${method.key} on camera $cameraId")
        val r = CameraRenderer(
            EglColorDepth.RGBA8888,
            sizes.preview.width,
            sizes.preview.height,
            hlg = false,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        ).also { renderer = it }
        r.previewRotation = previewRotation
        preview?.let(r::setPreview)
        val lost: (String) -> Unit = { reason -> _state.value = PhotoState.Failed(reason) }
        when {
            method.extension -> {
                val id = PhotoHdrProbe.EXTENSION_IDS.getValue(method)
                still = ExtensionStillStream.open(manager, cameraId, id, r.cameraSurface, sizes.jpeg, format, cameraHandler, lost)
            }
            method == HdrMethod.FUSION -> bracket = BracketCaptureStream.open(manager, cameraId, r.cameraSurface, sizes.jpeg, cameraHandler, lost)
            else -> still = StillCaptureStream.open(manager, cameraId, r.cameraSurface, sizes.jpeg, cameraHandler, format, lost)
        }
        return sizes
    }

    private fun extensionSizes(cameraId: String, method: HdrMethod, format: Int): PhotoSizes? {
        val ext = manager.getCameraExtensionCharacteristics(cameraId)
        val id = PhotoHdrProbe.EXTENSION_IDS.getValue(method)
        return PhotoSizes.choose(PhotoHdrProbe.extensionSizes(ext, id, format), PhotoHdrProbe.previewSizes(ext, id))
    }

    /** JPEG rotation (and preview turn) for a display rotated by [displayRotationDegrees]; 0 while closed. */
    fun uprightRotation(displayRotationDegrees: Int): Int = chars?.let { CameraController.uprightRotation(it, displayRotationDegrees) } ?: 0

    /** The display's rotation (0, 90, 180, 270); the preview is turned by it (see [CameraRenderer.previewRotation]). */
    fun setPreviewRotation(degrees: Int) {
        previewRotation = degrees
        renderer?.previewRotation = degrees
    }

    fun setPreview(surface: Surface?) {
        preview = surface
        renderer?.setPreview(surface)
    }

    /**
     * Takes one photo upright for [displayRotationDegrees] and saves it in [location] under the next free
     * `UC_…_CAM<id>_P<nnn>.jpg` name (Ultra HDR keeps .jpg); returns that name. Throws [IOException] when it cannot be
     * taken or saved.
     */
    @Suppress("TooGenericExceptionCaught") // Camera and HAL failures become IOException for the caller.
    suspend fun take(
        backend: StorageBackend,
        location: RecordingLocation,
        cameraId: String,
        displayRotationDegrees: Int
    ): String =
        withContext(Dispatchers.IO) {
            val ready = _state.value as? PhotoState.Ready ?: throw IOException("camera not ready")
            _state.value = ready.copy(capturing = true)
            try {
                val name = OutputNaming.nextPhoto(LocalDateTime.now(), cameraId, backend.listFileNames(location))
                val rotation = uprightRotation(displayRotationDegrees)
                val target = StorageTarget(backend, location, name)
                val b = bracket
                if (b != null) {
                    takeFused(b, ready, rotation, target)
                } else {
                    val s = still ?: throw IOException("camera not ready")
                    s.capture(rotation, StillCaptureStream.DEFAULT_QUALITY) { jpeg -> finishStill(ready, jpeg, target) }
                }
                name
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                (_state.value as? PhotoState.Ready)?.let { _state.value = it.copy(capturing = false, processing = false) }
            }
        }

    private class StorageTarget(val backend: StorageBackend, val location: RecordingLocation, val name: String)

    /** What the photo's metadata says was used, e.g. `fusion+ultra_hdr`, `extension_hdr`, `off`. */
    private fun methodLabel(plan: HdrPlan): String = plan.method.key + if (plan.ultraHdr && plan.method != HdrMethod.ULTRA_HDR) "+ultra_hdr" else ""

    /** The maker's extension JPEG is kept as it is (with a comment); every other camera JPEG gets the base rendering and the style hook. */
    private fun finishStill(ready: PhotoState.Ready, jpeg: ByteBuffer, target: StorageTarget) {
        val bytes = ByteArray(jpeg.remaining()).also { jpeg.get(it) }
        val method = methodLabel(ready.hdr)
        if (ready.hdr.method.extension) {
            writeTo(target) { out -> PhotoFinisher.stampVendorJpeg(bytes, method, out) }
        } else {
            (_state.value as? PhotoState.Ready)?.let { _state.value = it.copy(processing = true) }
            fusion.work.session { dir -> writeTo(target) { out -> PhotoFinisher.finishCameraJpeg(bytes, method, dir, out) } }
        }
    }

    /** Creates the photo, lets [write] fill it, fsyncs and closes; a failure leaves no file. */
    @Suppress("TooGenericExceptionCaught") // Whatever failed, the half-written photo must go.
    private inline fun writeTo(target: StorageTarget, write: (OutputFile) -> Unit) {
        val file = target.backend.createFile(target.location, target.name, MIME_TYPE)
        try {
            write(file)
            file.sync()
            file.close()
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw e
        }
    }

    /** Bracket −2/0/+2 EV to disk, then fuse off the camera thread (the "Processing…" state) and save. */
    private suspend fun takeFused(b: BracketCaptureStream, ready: PhotoState.Ready, rotation: Int, target: StorageTarget) {
        val files = arrayOfNulls<File>(BRACKET_EVS.size)
        fusion.work.session { dir ->
            val exposures = b.bracket(BRACKET_EVS) { i, image, _ -> files[i] = fusion.writeFrame(i, image) }
            (_state.value as? PhotoState.Ready)?.let { _state.value = it.copy(processing = true) }
            val frames = files.map { requireNotNull(it) { "bracket frame missing" } }
            val out = File(dir, "fused.jpg")
            withContext(Dispatchers.Default) {
                fusion.fuse(
                    frames,
                    exposures,
                    reference = BRACKET_EVS.indexOf(0.0),
                    width = ready.sizes.jpeg.width,
                    height = ready.sizes.jpeg.height,
                    rotation = rotation,
                    gainMap = ready.hdr.ultraHdr,
                    quality = StillCaptureStream.DEFAULT_QUALITY,
                    output = out,
                    render = true
                )
            }
            // EXIF is inserted, not rewritten, so an Ultra HDR file keeps its gain map (JpegMetadata).
            val meta = PhotoFinisher.fusedMeta(methodLabel(ready.hdr))
            writeTo(target) { file -> out.inputStream().use { input -> file.asStream().use { JpegMetadata.writeWithExif(input, it, meta) } } }
        }
    }

    /** Closes the camera (preview off). */
    fun close() {
        release()
        _state.value = PhotoState.Closed
    }

    private fun release() {
        still?.close()
        still = null
        bracket?.close()
        bracket = null
        renderer?.release()
        renderer = null
    }

    /** Frees the camera thread; the controller cannot be used afterwards. */
    fun shutdown() {
        release()
        cameraThread.quitSafely()
    }

    companion object {
        const val MIME_TYPE = "image/jpeg"

        /** Our bracket: −2, 0 and +2 EV around the metered exposure. */
        val BRACKET_EVS = listOf(-2.0, 0.0, 2.0)
        private const val HDR_PROBE_TIMEOUT_MS = 3_000L
    }
}
