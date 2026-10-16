package com.qtekfun.ultimatecamera.core.luts

import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.LutMath
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * The library preview: a [WIDTH]×[HEIGHT] image, top half a grey ramp, bottom half a hue sweep, run through the LUT with
 * [LutMath] (the CPU reference of the GPU shader) and written as PNG. 4 KB of pixels, no Bitmap, works on the JVM.
 */
object LutSwatch {
    const val WIDTH = 64
    const val HEIGHT = 16

    fun pixels(lut: CubeLut): IntArray = LutMath.applyToArgb(lut, input())

    fun write(lut: CubeLut, out: OutputStream) = PngWriter.writeArgb(WIDTH, HEIGHT, pixels(lut), out)

    /** The unprocessed swatch input (ARGB_8888). */
    fun input(): IntArray = IntArray(WIDTH * HEIGHT) { i ->
        val x = i % WIDTH
        val t = x / (WIDTH - 1f)
        val (r, g, b) = if (i / WIDTH < HEIGHT / 2) Triple(t, t, t) else hue(t)
        (0xFF shl 24) or (r.to8() shl 16) or (g.to8() shl 8) or b.to8()
    }

    private fun hue(t: Float): Triple<Float, Float, Float> {
        val h = t * 6f
        val f = h - h.toInt()
        return when (h.toInt() % 6) {
            0 -> Triple(1f, f, 0f)
            1 -> Triple(1f - f, 1f, 0f)
            2 -> Triple(0f, 1f, f)
            3 -> Triple(0f, 1f - f, 1f)
            4 -> Triple(f, 0f, 1f)
            else -> Triple(1f, 0f, 1f - f)
        }
    }

    private fun Float.to8(): Int = (coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}

/** Minimal PNG encoder: 8-bit RGB, no filtering, one zlib stream. Enough for small previews. */
internal object PngWriter {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    fun writeArgb(width: Int, height: Int, argb: IntArray, out: OutputStream) {
        require(width > 0 && height > 0 && argb.size == width * height) { "size mismatch" }
        out.write(SIGNATURE)
        val header = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeInt(width)
                writeInt(height)
                writeByte(8) // bit depth
                writeByte(2) // colour type RGB
                writeByte(0) // deflate
                writeByte(0) // adaptive filtering method
                writeByte(0) // no interlace
            }
        }.toByteArray()
        chunk(out, "IHDR", header)
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw, Deflater(Deflater.BEST_COMPRESSION)).use { z ->
            val row = ByteArray(1 + width * 3)
            for (y in 0 until height) {
                row[0] = 0 // filter: none
                for (x in 0 until width) {
                    val p = argb[y * width + x]
                    row[1 + x * 3] = (p shr 16).toByte()
                    row[2 + x * 3] = (p shr 8).toByte()
                    row[3 + x * 3] = p.toByte()
                }
                z.write(row)
            }
        }
        chunk(out, "IDAT", raw.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        out.flush()
    }

    private fun chunk(out: OutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        DataOutputStream(out).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
            flush()
        }
    }
}
