package com.qtekfun.ultimatecamera.feature.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import com.qtekfun.ultimatecamera.core.camera.StillCaptureStream
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import com.qtekfun.ultimatecamera.core.recording.PhotoController
import com.qtekfun.ultimatecamera.core.recording.PhotoFinisher.asStream
import com.qtekfun.ultimatecamera.core.recording.PhotoStyler
import com.qtekfun.ultimatecamera.core.storage.StorageBackend
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [RestyleStore] on the phone: reads the photo and its copy through [backend] (MediaStore or the picked folder), keeps
 * one small decoded preview source, and saves through the work folder on disk (never the whole JPEG in memory). Glue
 * only: the decisions are in [PhotoRestyle] and [com.qtekfun.ultimatecamera.core.recording.RestylePlan].
 */
class AndroidRestyleStore(context: Context, private val backend: StorageBackend) : RestyleStore<Bitmap> {
    private val work = File(context.cacheDir, "restyle")
    private val lock = Mutex()
    private var cached: Pair<String, Bitmap>? = null

    override suspend fun inspect(photo: SavedPhoto): StyleXmp.Info = withContext(Dispatchers.IO) {
        open(photo, photo.name).buffered().use { StyleXmp.inspect(it) }
    }

    override suspend fun exists(photo: SavedPhoto, name: String): Boolean = withContext(Dispatchers.IO) {
        backend.openRead(photo.location, name)?.use { true } ?: false
    }

    override suspend fun preview(photo: SavedPhoto, source: String, style: StyleParams): Bitmap = lock.withLock {
        withContext(Dispatchers.Default) {
            val key = "${photo.location}/$source"
            val base = cached?.takeIf { it.first == key }?.second ?: run {
                release()
                val orientation = open(photo, photo.name).use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
                PhotoStyler.preview({ open(photo, source) }, orientation, PREVIEW_SIDE).also { cached = key to it }
            }
            try {
                base.copy(Bitmap.Config.ARGB_8888, true).also { PhotoStyler.applyStyle(it, style) }
            } catch (e: OutOfMemoryError) {
                throw IOException("not enough memory for the preview", e)
            }
        }
    }

    override suspend fun save(photo: SavedPhoto, source: String, style: StyleParams, base: String?): String = lock.withLock {
        withContext(Dispatchers.IO) {
            // The preview's bitmap goes first: the full-size decode is the only big one in memory.
            release()
            val staged = File(work.apply { mkdirs() }, "source.jpg")
            try {
                open(photo, source).use { input -> staged.outputStream().use { input.copyTo(it, COPY_CHUNK) } }
                val name = StyleXmp.restyledName(photo.name, backend.listFileNames(photo.location))
                val file = backend.createFile(photo.location, name, PhotoController.MIME_TYPE)
                try {
                    file.asStream().use { out ->
                        PhotoStyler.restyleJpeg(staged, { open(photo, photo.name) }, out, style, StillCaptureStream.DEFAULT_QUALITY, base, work)
                    }
                    file.sync()
                    file.close()
                } catch (e: IOException) {
                    runCatching { file.delete() }
                    throw e
                } catch (e: OutOfMemoryError) {
                    // A very large photo on a modest phone: no new file, a note instead of a crash.
                    runCatching { file.delete() }
                    throw IOException("not enough memory", e)
                }
                name
            } finally {
                staged.delete()
            }
        }
    }

    override fun release() {
        cached?.second?.recycle()
        cached = null
    }

    private fun open(photo: SavedPhoto, name: String): InputStream = backend.openRead(photo.location, name) ?: throw IOException("$name is gone")

    private companion object {
        const val PREVIEW_SIDE = 1280
        const val COPY_CHUNK = 64 * 1024
    }
}
