package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The one place the photo style engine plugs in (D74, D79): it runs last on every photo we render ourselves, after the
 * base rendering and before encoding, on the upright ARGB_8888 bitmap (the gain map of an Ultra HDR photo is left
 * alone). [PhotoController] arms it per shot with [arm]: [stage] then applies the style ([PhotoStyler.applyStyle]) and,
 * when asked, first saves the unstyled render to [baseFile]; [writeWithExif] adds the style's XMP to the encoded file.
 */
object PhotoStyleHook {
    @Volatile
    var stage: (Bitmap) -> Unit = {}

    /** The style of the shot being finished (null = none), and the unstyled copy's name for its XMP. */
    @Volatile
    var style: StyleParams? = null

    @Volatile
    var baseName: String? = null

    /** Where [stage] puts the unstyled render when a base copy is wanted (null = no copy). */
    @Volatile
    var baseFile: File? = null

    /** Sets the hook for the next shot: [params] (identity or null = no-op), and [base] (file and name) for a copy. */
    fun arm(params: StyleParams?, base: Pair<File, String>? = null) {
        val styled = params?.takeUnless { it.isIdentity }
        style = styled
        baseFile = base?.first.takeIf { styled != null }
        baseName = base?.second.takeIf { styled != null }
        stage = if (styled == null) {
            {}
        } else {
            { bitmap ->
                baseFile?.let { file ->
                    file.parentFile?.mkdirs()
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, BASE_QUALITY, it) }
                }
                PhotoStyler.applyStyle(bitmap, styled)
            }
        }
    }

    /**
     * [JpegMetadata.writeWithExif], then the style XMP when a style is armed (staged in [workDir]: disk, not memory).
     * Files that already carry an XMP (Ultra HDR) keep theirs only.
     */
    fun writeWithExif(input: InputStream, output: OutputStream, meta: PhotoMeta, workDir: File) {
        val s = style
        if (s == null) {
            JpegMetadata.writeWithExif(input, output, meta)
            return
        }
        val staged = File(workDir.apply { mkdirs() }, "exif.jpg")
        try {
            staged.outputStream().buffered().use { JpegMetadata.writeWithExif(input, it, meta) }
            staged.inputStream().buffered().use { StyleXmp.insert(it, output, s, baseName) }
        } finally {
            staged.delete()
        }
    }

    private const val BASE_QUALITY = 95
}
