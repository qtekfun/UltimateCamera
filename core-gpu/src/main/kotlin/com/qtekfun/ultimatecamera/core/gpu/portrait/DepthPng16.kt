package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The depth of a portrait photo kept for editing after capture (D88): its inverse depth in dioptres with its real
 * range, as a 16-bit greyscale PNG any editor opens (white = near, 0 = no depth), upright like the photo. A `tEXt`
 * chunk [KEYWORD] carries the range (`near`, `far` in dioptres), the clockwise [Meta.rotation] from the sensor frame
 * and the capture look (aperture, focus point in the sensor frame), so the edit starts from what was shot.
 * Value v > 0 maps linearly to `far + (v − 1) / 65534 · (near − far)`. Pure JVM (zlib), no Android.
 */
object DepthPng16 {
    const val KEYWORD = "UltimateCamera:depth"
    private const val VERSION = 1
    private const val MAX_CODE = 65534
    private const val MAX_SIDE = 8192
    private val SIGNATURE = PngData.SIGNATURE

    /** What the file says next to the map; [focusX]/[focusY] are normalised in the sensor frame. */
    data class Meta(val rotation: Int, val fNumber: Float, val focusX: Float, val focusY: Float)

    /** A decoded file: the inverse depth in the sensor frame and the [meta]. */
    class Decoded(val depth: Plane, val meta: Meta)

    /** Writes the sensor-frame inverse [depth] turned upright by [meta]'s rotation, with [meta]. */
    fun write(depth: Plane, meta: Meta, output: OutputStream) {
        val upright = PlaneTurn.rotate(depth, meta.rotation)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in upright.data) {
            if (v <= Bokeh.INVALID) continue
            lo = minOf(lo, v)
            hi = maxOf(hi, v)
        }
        if (lo > hi) {
            lo = 0f
            hi = 0f
        }
        val span = hi - lo
        output.write(SIGNATURE)
        PngData.chunk(output, "IHDR") {
            int(it, upright.width)
            int(it, upright.height)
            it.write(byteArrayOf(BIT_DEPTH, 0, 0, 0, 0)) // 16-bit, greyscale, deflate, no filter method, no interlace.
        }
        val text = String.format(
            Locale.ROOT,
            "v=%d;near=%.9g;far=%.9g;rotation=%d;f=%.3g;fx=%.5f;fy=%.5f",
            VERSION,
            hi,
            lo,
            meta.rotation,
            meta.fNumber,
            meta.focusX,
            meta.focusY
        )
        PngData.chunk(output, "tEXt") { it.write((KEYWORD + "\u0000" + text).toByteArray(Charsets.ISO_8859_1)) }
        val row = ByteArray(2 * upright.width)
        PngData.idat(output, upright.height, row.size) { y, into ->
            for (x in 0 until upright.width) {
                val v = upright[x, y]
                val code = if (v <= Bokeh.INVALID) 0 else 1 + if (span > 0f) ((v - lo) / span * MAX_CODE).roundToInt().coerceIn(0, MAX_CODE) else 0
                into[2 * x] = (code shr BYTE).toByte()
                into[2 * x + 1] = code.toByte()
            }
        }
        PngData.chunk(output, "IEND") { }
    }

    /** Reads a file [write] made, back in the sensor frame. Throws [IOException] for anything else. */
    fun read(input: InputStream): Decoded {
        val data = DataInputStream(input)
        val signature = ByteArray(SIGNATURE.size)
        data.readFullyOrThrow(signature)
        if (!signature.contentEquals(SIGNATURE)) throw IOException("not a PNG")
        var width = 0
        var height = 0
        var text: String? = null
        val idat = ByteArrayOutputStream()
        while (true) {
            val length = data.readInt()
            if (length < 0) throw IOException("bad PNG chunk")
            val type = ByteArray(TYPE_LENGTH).also { data.readFullyOrThrow(it) }.toString(Charsets.ISO_8859_1)
            val body = ByteArray(length).also { data.readFullyOrThrow(it) }
            data.readInt() // CRC: zlib checks the image data itself.
            when (type) {
                "IHDR" -> {
                    checkHeader(body)
                    width = body.int(0)
                    height = body.int(INT_BYTES)
                }
                "tEXt" -> {
                    val s = body.toString(Charsets.ISO_8859_1)
                    if (s.startsWith(KEYWORD + "\u0000")) text = s.substring(KEYWORD.length + 1)
                }
                "IDAT" -> idat.write(body)
                "IEND" -> break
            }
        }
        val fields = parse(text ?: throw IOException("not a portrait depth map"))
        val near = fields.float("near")
        val far = fields.float("far")
        val meta = Meta(fields.float("rotation").toInt(), fields.float("f"), fields.float("fx"), fields.float("fy"))
        val upright = Plane(width, height)
        unfilter(InflaterInputStream(idat.toByteArray().inputStream()), width, height) { x, y, code ->
            upright[x, y] = if (code == 0) Bokeh.INVALID else far + (code - 1).toFloat() / MAX_CODE * (near - far)
        }
        return Decoded(PlaneTurn.rotate(upright, -meta.rotation), meta)
    }

    /** Throws unless the IHDR [body] is ours: 16-bit greyscale, not interlaced, a sane size. */
    private fun checkHeader(body: ByteArray) {
        if (body.size < IHDR_SIZE) throw IOException("bad PNG header")
        val ours = body[2 * INT_BYTES] == BIT_DEPTH && body[2 * INT_BYTES + 1] == 0.toByte() && body[2 * INT_BYTES + 4] == 0.toByte()
        if (!ours || body.int(0) !in 1..MAX_SIDE || body.int(INT_BYTES) !in 1..MAX_SIDE) throw IOException("not a 16-bit greyscale depth map")
    }

    /** The largest error [write] then [read] can add to a value of a map whose range is [span] dioptres. */
    fun quantum(span: Float): Float = abs(span) / MAX_CODE

    private fun unfilter(input: InputStream, width: Int, height: Int, sample: (Int, Int, Int) -> Unit) {
        val stride = 2 * width
        var previous = ByteArray(stride)
        var row = ByteArray(stride)
        val data = DataInputStream(input)
        for (y in 0 until height) {
            val filter = data.read()
            if (filter < 0) throw IOException("truncated depth map")
            data.readFullyOrThrow(row)
            for (i in 0 until stride) {
                val a = if (i >= 2) row[i - 2].toInt() and 0xFF else 0
                val b = previous[i].toInt() and 0xFF
                val c = if (i >= 2) previous[i - 2].toInt() and 0xFF else 0
                val add = when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> paeth(a, b, c)
                    else -> throw IOException("bad PNG filter $filter")
                }
                row[i] = ((row[i].toInt() and 0xFF) + add).toByte()
            }
            for (x in 0 until width) sample(x, y, (row[2 * x].toInt() and 0xFF) shl BYTE or (row[2 * x + 1].toInt() and 0xFF))
            val t = previous
            previous = row
            row = t
        }
    }

    private fun parse(text: String): Map<String, String> = text.split(';').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
    }.toMap()

    private fun Map<String, String>.float(key: String): Float =
        this[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: throw IOException("depth map without $key")

    private const val BIT_DEPTH: Byte = 16
    private const val BYTE = 8
    private const val INT_BYTES = 4
    private const val IHDR_SIZE = 13
    private const val TYPE_LENGTH = 4
}

/** An 8-bit greyscale PNG (the portrait's `…_depth.png`, D73): written by [PngData] like [DepthPng16], pure JVM. */
object GreyPng {
    /** Writes [grey] (`width * height` values, row 0 first) as an 8-bit greyscale PNG. */
    fun write(grey: ByteArray, width: Int, height: Int, output: OutputStream) {
        require(grey.size == width * height) { "grey ${grey.size} for ${width}x$height" }
        output.write(PngData.SIGNATURE)
        PngData.chunk(output, "IHDR") {
            int(it, width)
            int(it, height)
            it.write(byteArrayOf(BIT_DEPTH, 0, 0, 0, 0)) // 8-bit, greyscale, deflate, no filter method, no interlace.
        }
        PngData.idat(output, height, width) { y, into -> System.arraycopy(grey, y * width, into, 0, width) }
        PngData.chunk(output, "IEND") { }
    }

    private const val BIT_DEPTH: Byte = 8
}

/** The PNG plumbing [DepthPng16] and [GreyPng] share: chunks with their CRC and the image data. */
internal object PngData {
    val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** Filter type "Up": each byte minus the one above it; smooth depth maps compress far better than unfiltered. */
    const val FILTER_UP = 2

    /**
     * One IDAT chunk of [height] rows of [rowBytes] bytes, each filled by [fill] (row, into) and written with
     * [FILTER_UP] at deflate level 1: on a depth map that is smaller than unfiltered level 6 and several times faster
     * (D92).
     */
    inline fun idat(output: OutputStream, height: Int, rowBytes: Int, fill: (Int, ByteArray) -> Unit) {
        chunk(output, "IDAT") { out ->
            val deflater = Deflater(Deflater.BEST_SPEED)
            try {
                DeflaterOutputStream(out, deflater, BUFFER).use { z ->
                    var previous = ByteArray(rowBytes)
                    var row = ByteArray(rowBytes)
                    val filtered = ByteArray(1 + rowBytes)
                    filtered[0] = FILTER_UP.toByte()
                    for (y in 0 until height) {
                        fill(y, row)
                        for (i in 0 until rowBytes) filtered[1 + i] = (row[i] - previous[i]).toByte()
                        z.write(filtered)
                        val t = previous
                        previous = row
                        row = t
                    }
                }
            } finally {
                deflater.end()
            }
        }
    }

    inline fun chunk(output: OutputStream, type: String, body: (OutputStream) -> Unit) {
        val bytes = ByteArrayOutputStream().also(body).toByteArray()
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        int(output, bytes.size)
        output.write(typeBytes)
        output.write(bytes)
        val crc = CRC32().apply {
            update(typeBytes)
            update(bytes)
        }
        int(output, crc.value.toInt())
    }

    const val BUFFER = 64 * 1024
}

/** Quarter turns of a [Plane]. */
object PlaneTurn {
    /** [plane] turned clockwise by [degrees] (a multiple of 90; negative turns counter-clockwise). */
    fun rotate(plane: Plane, degrees: Int): Plane {
        val turn = ((degrees % FULL) + FULL) % FULL
        val w = plane.width
        val h = plane.height
        return when (turn) {
            0 -> Plane(w, h, plane.data.copyOf())
            QUARTER -> Plane(h, w).also { out -> for (y in 0 until h) for (x in 0 until w) out[h - 1 - y, x] = plane[x, y] }
            2 * QUARTER -> Plane(w, h).also { out -> for (y in 0 until h) for (x in 0 until w) out[w - 1 - x, h - 1 - y] = plane[x, y] }
            3 * QUARTER -> Plane(h, w).also { out -> for (y in 0 until h) for (x in 0 until w) out[y, w - 1 - x] = plane[x, y] }
            else -> throw IllegalArgumentException("not a quarter turn: $degrees")
        }
    }

    /** A normalised point of the sensor frame on the image turned clockwise by [degrees] (the upright photo). */
    fun toUpright(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (((degrees % FULL) + FULL) % FULL) {
        QUARTER -> (1f - y) to x
        2 * QUARTER -> (1f - x) to (1f - y)
        3 * QUARTER -> y to (1f - x)
        else -> x to y
    }

    /** The inverse of [toUpright]: a normalised point of the upright photo in the sensor frame. */
    fun toSensor(u: Float, v: Float, degrees: Int): Pair<Float, Float> = when (((degrees % FULL) + FULL) % FULL) {
        QUARTER -> v to (1f - u)
        2 * QUARTER -> (1f - u) to (1f - v)
        3 * QUARTER -> (1f - v) to u
        else -> u to v
    }

    private const val FULL = 360
    private const val QUARTER = 90
}

private const val SHIFT_BYTE = 8

internal fun int(output: OutputStream, v: Int) {
    output.write(v ushr 24)
    output.write(v ushr 16 and 0xFF)
    output.write(v ushr SHIFT_BYTE and 0xFF)
    output.write(v and 0xFF)
}

private fun ByteArray.int(at: Int): Int =
    (this[at].toInt() and 0xFF shl 24) or (this[at + 1].toInt() and 0xFF shl 16) or (this[at + 2].toInt() and 0xFF shl SHIFT_BYTE) or
        (this[at + 3].toInt() and 0xFF)

private fun DataInputStream.readFullyOrThrow(into: ByteArray) {
    try {
        readFully(into)
    } catch (e: EOFException) {
        throw IOException("truncated PNG", e)
    }
}

private fun paeth(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = abs(p - a)
    val pb = abs(p - b)
    val pc = abs(p - c)
    return if (pa <= pb && pa <= pc) {
        a
    } else if (pb <= pc) {
        b
    } else {
        c
    }
}
