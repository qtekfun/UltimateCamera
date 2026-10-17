package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import com.qtekfun.ultimatecamera.core.gpu.style.StyleEngine
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import java.io.File
import java.io.IOException

/**
 * The last step of the photo pipeline (D74): applies a style to a finished photo. Other photo processing (HDR fusion,
 * a tone curve) runs before it and calls [applyStyle] (or [StyleEngine.applyStyle] on its own pixel strips) last.
 */
object PhotoStyler {
    private const val STRIP_ROWS = 64

    /**
     * Applies [params] in place to a mutable ARGB_8888 [bitmap], in strips of 64 rows (one strip of pixels in memory at a
     * time, besides the bitmap). A gain map, if any, is left as it is.
     */
    fun applyStyle(bitmap: Bitmap, params: StyleParams) {
        if (params.isIdentity) return
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "needs a mutable ARGB_8888 bitmap" }
        val width = bitmap.width
        styleStrips(
            width,
            bitmap.height,
            params,
            read = { strip, y, rows -> bitmap.getPixels(strip, 0, width, 0, y, width, rows) },
            write = { strip, y, rows -> bitmap.setPixels(strip, 0, width, 0, y, width, rows) }
        )
    }

    /**
     * The strip loop of [applyStyle] for any image store: [read] fills the strip with `rows` rows from row `y`, [write]
     * stores them back. Also what a tiled pipeline (planes, a tile cache) can call.
     */
    fun styleStrips(width: Int, height: Int, params: StyleParams, read: (IntArray, Int, Int) -> Unit, write: (IntArray, Int, Int) -> Unit) {
        if (params.isIdentity || width <= 0 || height <= 0) return
        val strip = IntArray(width * minOf(STRIP_ROWS, height))
        var y = 0
        while (y < height) {
            val rows = minOf(STRIP_ROWS, height - y)
            read(strip, y, rows)
            StyleEngine.applyStyle(strip, params, 0, width * rows)
            write(strip, y, rows)
            y += rows
        }
    }

    /**
     * Decodes the JPEG [source], styles it, encodes it to [output] at [quality] with [source]'s EXIF, and records the
     * style (and the unstyled copy's name [base]) in its XMP. Ultra HDR sources keep their gain map (API 34+) and their
     * own XMP, so they get no style XMP and no EXIF copy (ExifInterface is not known to keep the gain map).
     */
    fun styleJpeg(source: File, output: File, params: StyleParams, quality: Int, base: String? = null) {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(source.path, options) ?: throw IOException("cannot decode ${source.name}")
        val gainMap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()
        val encoded = File(output.parentFile, output.name + ".tmp")
        try {
            applyStyle(bitmap, params)
            encoded.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG encoding failed") }
            bitmap.recycle()
            if (!gainMap) copyExif(source, encoded)
            encoded.inputStream().buffered().use { input -> output.outputStream().buffered().use { StyleXmp.insert(input, it, params, base) } }
        } finally {
            encoded.delete()
        }
    }

    /** Copies the tags a viewer or a gallery shows (time, camera, exposure, orientation, location) from [from] to [to]. */
    private fun copyExif(from: File, to: File) {
        runCatching {
            val source = ExifInterface(from.path)
            val target = ExifInterface(to.path)
            for (tag in EXIF_TAGS) source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            target.saveAttributes()
        }
    }

    @Suppress("DEPRECATION") // TAG_ISO_SPEED_RATINGS: the framework ExifInterface has no newer name for it.
    internal val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP
    )
}
