package com.qtekfun.ultimatecamera.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException

/**
 * Writes small text files (reports) to the public Downloads folder through MediaStore. No storage permission is needed
 * for the app's own files, the user finds them in the Files app, and `adb pull /sdcard/Download/<folder>` reads them.
 */
class DownloadsExporter(private val context: Context, private val folder: String) {
    fun write(displayName: String, mimeType: String, content: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore refused $displayName")
        try {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: throw IOException("cannot open $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
