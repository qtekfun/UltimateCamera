package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.qtekfun.ultimatecamera.core.gpu.hdr.BandSink
import com.qtekfun.ultimatecamera.core.gpu.hdr.PhotoLook
import com.qtekfun.ultimatecamera.core.gpu.hdr.RenderParams
import com.qtekfun.ultimatecamera.core.gpu.hdr.RowSource
import com.qtekfun.ultimatecamera.core.storage.OutputFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The last photo stage (D74) for every JPEG that is not the maker's own processing: the camera's plain JPEG or JPEG_R
 * is decoded (an Ultra HDR file keeps its gain map), the technical base rendering ([PhotoLook]) and then the style hook
 * ([PhotoStyleHook]) run in place on the bitmap, and the result is encoded again with our EXIF (the camera's
 * orientation, exposure, aperture, ISO and focal length kept) and the method used. The maker's extension JPEGs are kept
 * as they are, with only a comment naming the method.
 */
object PhotoFinisher {
    /** What the metadata calls the rendering ([RenderParams.BASE] then the style hook). */
    const val RENDER = "base"

    private const val TAG = "UcPhoto"
    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** Base rendering, then the style hook, in place on [bitmap] (ARGB_8888, mutable); returns the time in ms. */
    fun render(bitmap: Bitmap): Long {
        val start = SystemClock.elapsedRealtime()
        applyBase(bitmap)
        PhotoStyleHook.stage(bitmap)
        return SystemClock.elapsedRealtime() - start
    }

    /** The base rendering in place on [bitmap]; returns the time it took in ms. */
    fun applyBase(bitmap: Bitmap, params: RenderParams = RenderParams.BASE): Long {
        val start = SystemClock.elapsedRealtime()
        val w = bitmap.width
        val rows = ThreadLocal.withInitial { IntArray(w) }
        val source = RowSource { y, r, g, b ->
            val px = rows.get()
            bitmap.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = px[x]
                r[x] = ((p shr 16) and 0xFF) * INV_255
                g[x] = ((p shr 8) and 0xFF) * INV_255
                b[x] = (p and 0xFF) * INV_255
            }
        }
        val sink = BandSink { y0, n, r, g, b, _ ->
            val out = IntArray(n * w) { i -> OPAQUE or (q(r[i]) shl 16) or (q(g[i]) shl 8) or q(b[i]) }
            synchronized(bitmap) { bitmap.setPixels(out, 0, w, 0, y0, w, n) }
        }
        val look = PhotoLook(w, bitmap.height, params)
        look.apply(source, sink)
        val ms = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "render ${w}x${bitmap.height} in $ms ms, black point %.3f".format(look.blackPoint))
        return ms
    }

    /**
     * The camera's [jpeg] (JPEG or JPEG_R) rendered ([render]), written to [output] with EXIF naming [method]. The
     * re-encoded file is staged in [workDir] (disk, not memory) and streamed out.
     */
    fun finishCameraJpeg(jpeg: ByteArray, method: String, workDir: File, output: OutputFile) {
        val meta = cameraMeta(jpeg, PhotoMeta.describe(method, RENDER))
        val bitmap = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: throw IOException("camera JPEG does not decode")
        val staged = File(workDir.apply { mkdirs() }, "look.jpg")
        try {
            render(bitmap)
            FileOutputStream(staged).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)) { "JPEG encoding failed" } }
            val gainmap = hasGainmap(bitmap)
            bitmap.recycle()
            staged.inputStream().use { input -> output.asStream().use { PhotoStyleHook.writeWithExif(input, it, meta, workDir) } }
            Log.i(TAG, "saved ${meta.description} gainmap=$gainmap")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            staged.delete()
        }
    }

    /** The maker's own [jpeg] unchanged but for a comment naming [method]. */
    fun stampVendorJpeg(jpeg: ByteArray, method: String, output: OutputFile) {
        val description = PhotoMeta.describe(method, null)
        output.asStream().use { JpegMetadata.writeWithComment(ByteArrayInputStream(jpeg), it, description) }
        Log.i(TAG, "saved $description")
    }

    /** EXIF of a fused photo we encoded ourselves: upright pixels, this phone, now. */
    fun fusedMeta(method: String): PhotoMeta = PhotoMeta(
        description = PhotoMeta.describe(method, RENDER),
        make = Build.MANUFACTURER,
        model = Build.MODEL,
        dateTime = EXIF_TIME.format(LocalDateTime.now())
    )

    /** The camera EXIF worth keeping (read-only parse of [jpeg]); our own description replaces the rest. */
    private fun cameraMeta(jpeg: ByteArray, description: String): PhotoMeta {
        val exif = runCatching { ExifInterface(ByteArrayInputStream(jpeg)) }.getOrNull()
        fun double(tag: String) = exif?.getAttributeDouble(tag, Double.NaN)?.takeUnless { it.isNaN() || it <= 0.0 }
        return PhotoMeta(
            description = description,
            make = exif?.getAttribute(ExifInterface.TAG_MAKE) ?: Build.MANUFACTURER,
            model = exif?.getAttribute(ExifInterface.TAG_MODEL) ?: Build.MODEL,
            dateTime = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: EXIF_TIME.format(LocalDateTime.now()),
            orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL,
            exposureTimeS = double(ExifInterface.TAG_EXPOSURE_TIME),
            fNumber = double(ExifInterface.TAG_F_NUMBER),
            iso = exif?.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)?.takeIf { it > 0 },
            focalLengthMm = double(ExifInterface.TAG_FOCAL_LENGTH)
        )
    }

    private fun hasGainmap(bitmap: Bitmap): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !bitmap.isRecycled && bitmap.hasGainmap()

    /** [OutputFile] as a stream, for [JpegMetadata]; closing it does not close the file. */
    fun OutputFile.asStream(): OutputStream {
        val file = this
        return object : OutputStream() {
            override fun write(b: Int) {
                file.write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                file.write(b, off, len)
            }
        }.buffered(STREAM_BUFFER)
    }

    private fun q(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    const val QUALITY = 95
    private const val INV_255 = 1f / 255f
    private const val OPAQUE = -0x1000000
    private const val STREAM_BUFFER = 64 * 1024
}
