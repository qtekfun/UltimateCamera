package com.qtekfun.ultimatecamera.core.luts

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A document picked through the Storage Access Framework (`ACTION_OPEN_DOCUMENT`). The library copies it in, so no
 * persistable permission is needed. The display name (and so the format) comes from [OpenableColumns.DISPLAY_NAME].
 */
class ContentUriLutSource(private val resolver: ContentResolver, private val uri: Uri) : LutSource {
    override val displayName: String by lazy {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    }

    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open $uri")
}
