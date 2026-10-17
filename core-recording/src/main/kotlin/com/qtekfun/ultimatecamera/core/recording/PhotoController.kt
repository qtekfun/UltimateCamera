package com.qtekfun.ultimatecamera.core.recording

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.qtekfun.ultimatecamera.core.camera.BracketCaptureStream
import com.qtekfun.ultimatecamera.core.camera.DepthCaptureStream
import com.qtekfun.ultimatecamera.core.camera.DepthSource
import com.qtekfun.ultimatecamera.core.camera.DepthSupport
import com.qtekfun.ultimatecamera.core.camera.ExtensionStillStream
import com.qtekfun.ultimatecamera.core.camera.HdrChoice
import com.qtekfun.ultimatecamera.core.camera.HdrMethod
import com.qtekfun.ultimatecamera.core.camera.HdrPlan
import com.qtekfun.ultimatecamera.core.camera.HdrSelector
import com.qtekfun.ultimatecamera.core.camera.HdrSupport
import com.qtekfun.ultimatecamera.core.camera.PhotoHdrProbe
import com.qtekfun.ultimatecamera.core.camera.PhotoSizes
import com.qtekfun.ultimatecamera.core.camera.PortraitProbe
import com.qtekfun.ultimatecamera.core.camera.PortraitSelector
import com.qtekfun.ultimatecamera.core.camera.SessionRejectedException
import com.qtekfun.ultimatecamera.core.camera.StillCaptureStream
import com.qtekfun.ultimatecamera.core.camera.StillSource
import com.qtekfun.ultimatecamera.core.gpu.gl.CameraRenderer
import com.qtekfun.ultimatecamera.core.gpu.gl.EglColorDepth
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
        val processing: Boolean = false,
        /** The depth source the portrait shot uses (D73), null when portrait is off or the session fell back. */
        val portrait: DepthSource? = null,
        /** The depth sources this camera offers; empty with Settings › Photo › Portrait off (nothing probed). */
        val portraitSources: List<DepthSource> = emptyList()
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
    private var depthStream: DepthCaptureStream? = null
    private val portraitRender = PortraitProcessor(File(context.cacheDir, "portrait"))
    private val depthCache = HashMap<String, DepthSupport>()
    private var renderer: CameraRenderer? = null
    private var chars: CameraCharacteristics? = null
    private var preview: Surface? = null
    private var previewRotation = 0
    private val supportCache = HashMap<String, HdrSupport>()

    /** The HDR choice the next [open] uses. */
    var hdrChoice: HdrChoice = HdrChoice.Auto

    /** Portrait for the next [open] (D73): the setting, the Experimental switch and the chip. */
    var portraitOptions: PortraitOptions = PortraitOptions()

    /** How the next portrait shot is rendered (aperture, focus, depth PNG); no reopen needed. */
    var portraitLook: PortraitLook = PortraitLook(fNumber = DEFAULT_F_NUMBER)

    /** Opens [cameraId] for photos with [hdrChoice] and starts the preview. Closes whatever was open. */
    @Suppress("TooGenericExceptionCaught") // Vendor HALs throw anything; the screen shows it instead of crashing.
    suspend fun open(cameraId: String) = withContext(Dispatchers.Default) {
        close()
        val support = hdrSupport(cameraId)
        val methods = HdrSelector.available(support)
        var plan = HdrSelector.plan(support, hdrChoice, gainMapEncoder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val options = portraitOptions
        val depth = if (options.enabled) depthSupport(cameraId) else DepthSupport()
        val portraitSources = PortraitSelector.offered(options.enabled, options.experimental) { depth }
        var portrait = portraitSources.firstOrNull()?.takeIf { options.on }
        try {
            val c = manager.getCameraCharacteristics(cameraId).also { chars = it }
            val sizes = try {
                portrait?.let { openPortrait(cameraId, c, it, depth) } ?: openWith(cameraId, c, plan)
            } catch (e: Exception) {
                if (plan.method == HdrMethod.OFF && portrait == null) throw e
                // The HDR or portrait session was refused: plain JPEG rather than no camera (the chips then show it).
                release()
                plan = HdrPlan(HdrMethod.OFF, ultraHdr = false)
                portrait = null
                openWith(cameraId, c, plan)
            }
            if (portrait != null) plan = HdrPlan(HdrMethod.OFF, ultraHdr = false)
            _state.value = PhotoState.Ready(sizes, hdr = plan, hdrMethods = methods, portrait = portrait, portraitSources = portraitSources)
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

    /** What [cameraId] offers for depth (D73): bounded and cached like [hdrSupport]; probed only with portrait enabled. */
    private suspend fun depthSupport(cameraId: String): DepthSupport = depthCache[cameraId] ?: run {
        val probe = CoroutineScope(Dispatchers.IO).async { PortraitProbe.read(manager, cameraId) }
        (withTimeoutOrNull(HDR_PROBE_TIMEOUT_MS) { probe.await() } ?: DepthSupport().also { probe.cancel() }).also { depthCache[cameraId] = it }
    }

    /**
     * Opens the portrait session of [source] (D73): the maker's `EXTENSION_BOKEH` (its result saved as is), or our
     * depth session (DEPTH16 or the stereo pair) whose shots [PortraitProcessor] blurs.
     */
    private suspend fun openPortrait(cameraId: String, c: CameraCharacteristics, source: DepthSource, depth: DepthSupport): PhotoSizes {
        val bokeh = CameraExtensionCharacteristics.EXTENSION_BOKEH
        val sizes = if (source == DepthSource.VENDOR_BOKEH) {
            val ext = manager.getCameraExtensionCharacteristics(cameraId)
            PhotoSizes.choose(PhotoHdrProbe.extensionSizes(ext, bokeh, ImageFormat.JPEG), PhotoHdrProbe.previewSizes(ext, bokeh))
        } else {
            DepthCaptureStream.sizes(c)
        } ?: throw SessionRejectedException("no portrait output on camera $cameraId")
        val r = newRenderer(c, sizes)
        val lost: (String) -> Unit = { reason -> _state.value = PhotoState.Failed(reason) }
        if (source == DepthSource.VENDOR_BOKEH) {
            still = ExtensionStillStream.open(manager, cameraId, bokeh, r.cameraSurface, sizes.jpeg, ImageFormat.JPEG, cameraHandler, lost)
        } else {
            depthStream = DepthCaptureStream.open(manager, cameraId, source, depth, r.cameraSurface, sizes.jpeg, cameraHandler, lost)
        }
        return sizes
    }

    private fun newRenderer(c: CameraCharacteristics, sizes: PhotoSizes): CameraRenderer {
        val r = CameraRenderer(
            EglColorDepth.RGBA8888,
            sizes.preview.width,
            sizes.preview.height,
            hlg = false,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        ).also { renderer = it }
        r.previewRotation = previewRotation
        preview?.let(r::setPreview)
        return r
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
        val r = newRenderer(c, sizes)
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
    suspend fun take(backend: StorageBackend, location: RecordingLocation, cameraId: String, displayRotationDegrees: Int): String =
        withContext(Dispatchers.IO) {
            val ready = _state.value as? PhotoState.Ready ?: throw IOException("camera not ready")
            _state.value = ready.copy(capturing = true)
            try {
                val name = OutputNaming.nextPhoto(LocalDateTime.now(), cameraId, backend.listFileNames(location))
                val rotation = uprightRotation(displayRotationDegrees)
                val b = bracket
                val d = depthStream
                if (d != null) {
                    takePortrait(d, rotation, StorageTarget(backend, location, name))
                } else if (b != null) {
                    takeFused(b, ready, rotation, StorageTarget(backend, location, name))
                } else {
                    val s = still ?: throw IOException("camera not ready")
                    s.capture(rotation, StillCaptureStream.DEFAULT_QUALITY) { jpeg -> save(backend, location, name, jpeg) }
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

    /** Bracket −2/0/+2 EV to disk, then fuse off the camera thread (the "Processing…" state) and save. */
    private suspend fun takeFused(b: BracketCaptureStream, ready: PhotoState.Ready, rotation: Int, target: StorageTarget) {
        val files = arrayOfNulls<File>(BRACKET_EVS.size)
        try {
            val exposures = b.bracket(BRACKET_EVS) { i, image, _ -> files[i] = fusion.writeFrame(i, image) }
            (_state.value as? PhotoState.Ready)?.let { _state.value = it.copy(processing = true) }
            val frames = files.map { requireNotNull(it) { "bracket frame missing" } }
            val out = File(frames[0].parentFile, "fused.jpg")
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
                    output = out
                )
            }
            // ExifInterface rewrites the file and is not known to keep the gain map, so Ultra HDR files go untagged.
            if (!ready.hdr.ultraHdr) PhotoExif.stamp(out, LocalDateTime.now())
            copyToStorage(target, out)
        } finally {
            fusion.clear()
        }
    }

    /**
     * One portrait shot (D73): JPEG and depth in one request, then the blur off the camera thread ("Processing…"),
     * saved as [StorageTarget.name] and, when asked for, the depth map as `…_depth.png` next to it.
     */
    private suspend fun takePortrait(d: DepthCaptureStream, rotation: Int, target: StorageTarget) {
        val shot = d.capture()
        (_state.value as? PhotoState.Ready)?.let { _state.value = it.copy(processing = true) }
        val out = withContext(Dispatchers.Default) { portraitRender.render(shot, portraitLook, rotation, StillCaptureStream.DEFAULT_QUALITY) }
        try {
            copyToStorage(target, out.jpeg)
            out.depthPng?.let { png -> copyToStorage(StorageTarget(target.backend, target.location, depthName(target.name)), png, PNG_MIME_TYPE) }
        } finally {
            out.jpeg.delete()
            out.depthPng?.delete()
        }
    }

    /** Copies [source] to storage in small chunks (never the whole JPEG in memory), fsyncs and closes. */
    private fun copyToStorage(target: StorageTarget, source: File, mimeType: String = MIME_TYPE) {
        val file = target.backend.createFile(target.location, target.name, mimeType)
        try {
            source.inputStream().use { input ->
                val chunk = ByteArray(COPY_CHUNK)
                var n = input.read(chunk)
                while (n >= 0) {
                    file.write(chunk, 0, n)
                    n = input.read(chunk)
                }
            }
            file.sync()
            file.close()
        } catch (e: IOException) {
            runCatching { file.delete() }
            throw e
        }
    }

    /** Writes the JPEG bytes straight from the image buffer, fsyncs and closes; a failed write leaves no file. */
    private fun save(backend: StorageBackend, location: RecordingLocation, name: String, jpeg: ByteBuffer) {
        val bytes = ByteArray(jpeg.remaining()).also { jpeg.get(it) }
        val file = backend.createFile(location, name, MIME_TYPE)
        try {
            file.write(bytes, 0, bytes.size)
            file.sync()
            file.close()
        } catch (e: IOException) {
            runCatching { file.delete() }
            throw e
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
        depthStream?.close()
        depthStream = null
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
        const val PNG_MIME_TYPE = "image/png"

        /** The portrait slider's starting aperture. */
        const val DEFAULT_F_NUMBER = 2.8f

        /** The depth map's name next to photo [photoName]: `…_P<nnn>.jpg` → `…_P<nnn>_depth.png` (D73). */
        fun depthName(photoName: String): String = photoName.substringBeforeLast('.') + "_depth.png"

        /** Our bracket: −2, 0 and +2 EV around the metered exposure. */
        val BRACKET_EVS = listOf(-2.0, 0.0, 2.0)
        private const val HDR_PROBE_TIMEOUT_MS = 3_000L
        private const val COPY_CHUNK = 64 * 1024
    }
}

/** Portrait for [PhotoController.open] (D73): the setting ([enabled]), Experimental (stereo) and the chip ([on]). */
data class PortraitOptions(val enabled: Boolean = false, val experimental: Boolean = false, val on: Boolean = false)
