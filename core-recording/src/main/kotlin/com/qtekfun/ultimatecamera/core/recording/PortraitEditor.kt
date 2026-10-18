package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.portrait.PortraitKernels
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Edit after capture of a portrait photo (REQ-PHO-003, D88): a new aperture and focus point rendered from the
 * unblurred photo (`…_sharp.jpg`) and the depth with its real range (`…_depth16.png`) with the capture's own steps
 * ([PortraitProcessor.blurPhoto]: refinement, guided upsampling, gather blur on [kernels], GPU with CPU fallback), then
 * the photo's style, if it has one. One full-size bitmap in memory at a time, as at capture; call off the main thread
 * (the kernels belong to the calling thread for the call).
 */
class PortraitEditor(private val work: File, private val kernels: () -> PortraitKernels) {
    /** The unblurred photo decoded for previews, sensor frame, at most [maxSide] on its long side (power-of-two sampled). */
    fun decodePreview(open: () -> InputStream, maxSide: Int): Bitmap = PhotoStyler.preview(open, ExifInterface.ORIENTATION_NORMAL, maxSide)

    /** A preview of [look] on a copy of [source] (from [decodePreview]) with [style], turned upright by [rotation]. */
    fun preview(source: Bitmap, depth: Plane, look: PortraitLook, style: StyleParams?, rotation: Int): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            kernels().use { k -> PortraitProcessor.blurPhoto(copy, depth, look, k) }
            style?.let { PhotoStyler.applyStyle(copy, it) }
        } catch (e: OutOfMemoryError) {
            copy.recycle()
            throw IOException("not enough memory for the preview", e)
        }
        return upright(copy, rotation)
    }

    /**
     * The full-size edit: decodes [sharp], blurs it for [look], writes the unstyled result to [unstyled] when given
     * (the copy a styled edit keeps, so it can be restyled), applies [style] and writes [output] with the header of the
     * photo being edited ([original]: its EXIF and comments, so time, camera and orientation stay) and a style XMP
     * naming [base], as a restyle does (D85). Throws [IOException], also for an out-of-memory on a huge photo.
     */
    @Suppress("LongParameterList")
    fun save(
        sharp: File,
        original: () -> InputStream,
        depth: Plane,
        look: PortraitLook,
        style: StyleParams?,
        base: String?,
        quality: Int,
        output: OutputStream,
        unstyled: OutputStream?
    ) {
        work.mkdirs()
        val encoded = File(work, "edited.jpg")
        val plain = File(work, "edited_plain.jpg")
        val bitmap = try {
            BitmapFactory.decodeFile(sharp.path, PortraitProcessor.mutableArgb()) ?: throw IOException("cannot decode ${sharp.name}")
        } catch (e: OutOfMemoryError) {
            throw IOException("not enough memory", e)
        }
        val timer = StageTimer()
        timer.mark("decode")
        try {
            val used = kernels().use { k ->
                PortraitProcessor.blurPhoto(bitmap, depth, look, k, timer)
                k.name
            }
            val styled = style?.takeUnless { it.isIdentity }
            if (unstyled != null) {
                encode(bitmap, quality, plain)
                withHeader(original, plain, unstyled, STANDARD, null)
                timer.mark("unstyled copy")
            }
            styled?.let { PhotoStyler.applyStyle(bitmap, it) }
            timer.mark("style")
            encode(bitmap, quality, encoded)
            bitmap.recycle()
            withHeader(original, encoded, output, styled ?: STANDARD, base)
            timer.mark("encode")
            Log.i(TAG, "edit ${look.fNumber} on the $used: $timer")
        } catch (e: OutOfMemoryError) {
            throw IOException("not enough memory", e)
        } finally {
            bitmap.recycle()
            encoded.delete()
            plain.delete()
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int, file: File) {
        file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG encoding failed") }
    }

    private fun withHeader(original: () -> InputStream, encoded: File, output: OutputStream, style: StyleParams, base: String?) {
        original().buffered().use { header -> encoded.inputStream().buffered().use { StyleXmp.restyle(header, it, output, style, base) } }
    }

    private fun upright(bitmap: Bitmap, rotation: Int): Bitmap {
        val turn = ((rotation % FULL_TURN) + FULL_TURN) % FULL_TURN
        if (turn == 0) return bitmap
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private companion object {
        const val TAG = "UcPortrait"
        const val FULL_TURN = 360
        val STANDARD = StyleParams.of(StylePreset.STANDARD)
    }
}
