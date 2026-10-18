package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import com.qtekfun.ultimatecamera.core.gpu.style.GpuStyle
import com.qtekfun.ultimatecamera.core.gpu.style.StyleEngine
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The last step of the photo pipeline (D79): applies a style to a finished photo. Other photo processing (HDR fusion,
 * a tone curve) runs before it and calls [applyStyle] (or [StyleEngine.applyStyle] on its own pixel strips) last.
 */
object PhotoStyler {
    private const val STRIP_ROWS = 256
    private const val TAG = "UcPhoto"
    private const val NS_PER_MS = 1_000_000L

    /**
     * Applies [params] in place to a mutable ARGB_8888 [bitmap], in strips of 256 rows (one strip of pixels in memory at
     * a time, besides the bitmap), on the GPU when it is worth it ([styleStrips]). A gain map, if any, is left as it is.
     */
    fun applyStyle(bitmap: Bitmap, params: StyleParams, gpu: Boolean = true) {
        if (params.isIdentity) return
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "needs a mutable ARGB_8888 bitmap" }
        val width = bitmap.width
        styleStrips(
            width,
            bitmap.height,
            params,
            read = { strip, y, rows -> bitmap.getPixels(strip, 0, width, 0, y, width, rows) },
            write = { strip, y, rows -> bitmap.setPixels(strip, 0, width, 0, y, width, rows) },
            gpu = gpu
        )
    }

    /**
     * The strip loop of [applyStyle] for any image store: [read] fills the strip with `rows` rows from row `y`, [write]
     * stores them back. Also what a tiled pipeline (planes, a tile cache) can call. Photos of half a megapixel or more go
     * through the GPU ([GpuStyle], D90); smaller ones, and the rest of a photo whose GPU pass failed ([PhotoGpu]), are
     * styled on the CPU with each strip split across all cores. Returns "gpu" or "cpu".
     */
    fun styleStrips(
        width: Int,
        height: Int,
        params: StyleParams,
        read: (IntArray, Int, Int) -> Unit,
        write: (IntArray, Int, Int) -> Unit,
        gpu: Boolean = true
    ): String {
        if (params.isIdentity || width <= 0 || height <= 0) return "none"
        val start = System.nanoTime()
        val lut = StyleEngine.lutFor(params)
        val lutNs = System.nanoTime() - start
        val written = PhotoFinisher.RowsWritten(write)
        val path = PhotoGpu.attempt("style", gpu && PhotoGpu.worth(width, height), gpu = {
            GpuStyle().use { it.apply(lut, width, height, read, written) }
        }) { styleOnCpu(width, height, params, read, write, written.done) }
        if (width.toLong() * height >= PhotoGpu.MIN_PIXELS) {
            Log.i(TAG, "style ${params.preset.id} ${width}x$height in ${(System.nanoTime() - start) / NS_PER_MS} ms (LUT ${lutNs / NS_PER_MS} ms) on the $path")
        }
        return path
    }

    /** [StyleEngine.applyStyle] over rows [from] to [height] in strips, each strip split into one range per core. */
    @Suppress("LongParameterList")
    internal fun styleOnCpu(
        width: Int,
        height: Int,
        params: StyleParams,
        read: (IntArray, Int, Int) -> Unit,
        write: (IntArray, Int, Int) -> Unit,
        from: Int = 0
    ) {
        if (from >= height) return
        val strip = IntArray(width * minOf(STRIP_ROWS, height - from))
        val cores = Runtime.getRuntime().availableProcessors()
        val pool = Executors.newFixedThreadPool(cores)
        try {
            var y = from
            while (y < height) {
                val rows = minOf(STRIP_ROWS, height - y)
                read(strip, y, rows)
                val n = width * rows
                val step = (n + cores - 1) / cores
                pool.invokeAll(
                    (0 until n step step).map { o ->
                        Callable { StyleEngine.applyStyle(strip, params, o, minOf(step, n - o)) }
                    }
                ).forEach { it.get() }
                write(strip, y, rows)
                y += rows
            }
        } finally {
            pool.shutdown()
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

    /**
     * A restyle of a saved photo (D79 follow-up): decodes the unstyled [source] (the `_base` copy, or an unstyled photo),
     * applies [params] in strips, encodes it at [quality] in [work], then writes it to [output] with the header of the
     * styled photo ([original]: its EXIF and comment, so the shot's time, camera and orientation stay) and a new style
     * XMP naming [base]. One full-size bitmap in memory at a time, as at capture; the encoded file goes through disk.
     */
    fun restyleJpeg(source: File, original: () -> InputStream, output: OutputStream, params: StyleParams, quality: Int, base: String?, work: File) {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(source.path, options) ?: throw IOException("cannot decode ${source.name}")
        val encoded = File(work.apply { mkdirs() }, "restyled.jpg")
        try {
            styleAndEncode(bitmap, params, quality, encoded)
            original().buffered().use { header -> encoded.inputStream().buffered().use { StyleXmp.restyle(header, it, output, params, base) } }
        } finally {
            encoded.delete()
        }
    }

    /** Styles [bitmap] in place, encodes it to [file] and recycles it, whatever happens. */
    private fun styleAndEncode(bitmap: Bitmap, params: StyleParams, quality: Int, file: File) {
        try {
            applyStyle(bitmap, params)
            file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG encoding failed") }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A small upright preview of the JPEG [open] gives (opened twice: bounds, then pixels), at most [maxSide] pixels on
     * the long side (decoded with a power-of-two sample size, so a 50 MP photo never sits in memory), turned by the EXIF
     * [orientation] of the photo it stands for. Mutable ARGB_8888, ready for [applyStyle].
     */
    fun preview(open: () -> InputStream, orientation: Int, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxSide)
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("cannot decode")
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> QUARTER
            ExifInterface.ORIENTATION_ROTATE_180 -> 2 * QUARTER
            ExifInterface.ORIENTATION_ROTATE_270 -> 3 * QUARTER
            else -> 0
        }
        if (degrees == 0) return decoded
        val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (turned !== decoded) decoded.recycle()
        return if (turned.isMutable) turned else turned.copy(Bitmap.Config.ARGB_8888, true).also { turned.recycle() }
    }

    /** The largest power-of-two sample size that keeps a [longSide]-pixel image at least [maxSide] pixels long. */
    internal fun sampleSize(longSide: Int, maxSide: Int): Int {
        var sample = 1
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private const val QUARTER = 90

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
