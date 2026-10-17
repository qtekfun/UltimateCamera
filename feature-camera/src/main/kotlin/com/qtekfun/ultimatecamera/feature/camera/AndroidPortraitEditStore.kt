package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.qtekfun.ultimatecamera.core.camera.StillCaptureStream
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthPng16
import com.qtekfun.ultimatecamera.core.gpu.portrait.FallbackPortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.GpuPortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.portrait.KernelHealth
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import com.qtekfun.ultimatecamera.core.recording.PhotoController
import com.qtekfun.ultimatecamera.core.recording.PhotoFinisher.asStream
import com.qtekfun.ultimatecamera.core.recording.PortraitEditor
import com.qtekfun.ultimatecamera.core.recording.PortraitFiles
import com.qtekfun.ultimatecamera.core.recording.PortraitLook
import com.qtekfun.ultimatecamera.core.recording.RestylePlan
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [PortraitEditStore] on the phone (D88): finds the shot's `…_sharp.jpg` and `…_depth16.png` through [backend], keeps
 * one small decoded preview source and the depth, and renders with [PortraitEditor] (GPU kernels, the CPU as fallback,
 * remembered for the process). Saves go through the work folder on disk. Glue only: the decisions are in
 * [PortraitEdit] and [PortraitFiles].
 */
class AndroidPortraitEditStore(context: Context, private val backend: StorageBackend) : PortraitEditStore<Bitmap> {
    private val work = File(context.cacheDir, "portrait-edit")
    private val lock = Mutex()
    private val health = KernelHealth()
    private val editor = PortraitEditor(work) {
        FallbackPortraitKernels(health, ::GpuPortraitKernels) { reason -> Log.w(TAG, "portrait edit: GPU kernels failed ($reason), CPU from now on") }
    }
    private var cachedKey: String? = null
    private var cachedPhoto: Bitmap? = null
    private var cachedDepth: Plane? = null

    override suspend fun find(photo: SavedPhoto): PortraitSource? = withContext(Dispatchers.IO) {
        val sharp = PortraitFiles.sharpName(photo.name)
        val depth = PortraitFiles.depthName(photo.name)
        if (backend.openRead(photo.location, sharp)?.use { true } != true) return@withContext null
        val meta = backend.openRead(photo.location, depth)?.buffered()?.use { DepthPng16.read(it).meta } ?: return@withContext null
        PortraitSource(sharp, depth, meta)
    }

    override suspend fun preview(photo: SavedPhoto, source: PortraitSource, look: PortraitLook): Bitmap = lock.withLock {
        withContext(Dispatchers.Default) {
            val key = "${photo.location}/${source.sharp}"
            if (cachedKey != key) {
                release()
                cachedDepth = readDepth(photo, source)
                cachedPhoto = try {
                    editor.decodePreview({ open(photo, source.sharp) }, PREVIEW_SIDE)
                } catch (e: OutOfMemoryError) {
                    throw IOException("not enough memory for the preview", e)
                }
                cachedKey = key
            }
            editor.preview(requireNotNull(cachedPhoto), requireNotNull(cachedDepth), look, style(photo), source.meta.rotation)
        }
    }

    override suspend fun save(photo: SavedPhoto, source: PortraitSource, look: PortraitLook): String = lock.withLock {
        withContext(Dispatchers.Default) {
            // The preview's bitmap goes first: the full-size decode is the only big one in memory.
            val depth = cachedDepth?.takeIf { cachedKey == "${photo.location}/${source.sharp}" } ?: readDepth(photo, source)
            release()
            val staged = File(work.apply { mkdirs() }, "sharp.jpg")
            try {
                open(photo, source.sharp).use { input -> staged.outputStream().use { input.copyTo(it, COPY_CHUNK) } }
                val info = inspect(photo)
                val style = info.style?.takeUnless { it.isIdentity }
                val taken = backend.listFileNames(photo.location)
                val name = PortraitFiles.editedName(photo.name, taken)
                // A styled edit keeps its unstyled copy when the photo had one, so it can be restyled like the photo.
                val baseName = StyleXmp.baseName(name).takeIf { style != null && RestylePlan.baseCandidate(photo.name, info) in taken }
                val file = backend.createFile(photo.location, name, PhotoController.MIME_TYPE)
                val base = baseName?.let { backend.createFile(photo.location, it, PhotoController.MIME_TYPE) }
                try {
                    file.asStream().use { out ->
                        val unstyled = base?.asStream()
                        try {
                            editor.save(staged, { open(photo, photo.name) }, depth, look, style, baseName, StillCaptureStream.DEFAULT_QUALITY, out, unstyled)
                        } finally {
                            unstyled?.close()
                        }
                    }
                    finishFile(file)
                    base?.let(::finishFile)
                } catch (e: IOException) {
                    runCatching { file.delete() }
                    base?.let { runCatching { it.delete() } }
                    throw e
                }
                name
            } finally {
                staged.delete()
            }
        }
    }

    private fun finishFile(file: OutputFile) {
        file.sync()
        file.close()
    }

    private fun style(photo: SavedPhoto): StyleParams? = inspect(photo).style?.takeUnless { it.isIdentity }

    private fun inspect(photo: SavedPhoto): StyleXmp.Info = open(photo, photo.name).buffered().use { StyleXmp.inspect(it) }

    private fun readDepth(photo: SavedPhoto, source: PortraitSource): Plane = open(photo, source.depth).buffered().use { DepthPng16.read(it).depth }

    override fun release() {
        cachedPhoto?.recycle()
        cachedPhoto = null
        cachedDepth = null
        cachedKey = null
    }

    private fun open(photo: SavedPhoto, name: String): InputStream = backend.openRead(photo.location, name) ?: throw IOException("$name is gone")

    private companion object {
        const val TAG = "UcPortrait"
        const val PREVIEW_SIDE = 1280
        const val COPY_CHUNK = 64 * 1024
    }
}
