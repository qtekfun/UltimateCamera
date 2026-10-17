package com.qtekfun.ultimatecamera.core.recording

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToLong

/**
 * What our photos record about themselves (D74): the usual EXIF facts plus, in ImageDescription and a JPEG comment,
 * how the photo was made (`UltimateCamera hdr=fusion+ultra_hdr render=base`), so a report can tell the methods apart.
 */
data class PhotoMeta(
    val description: String,
    val make: String = "",
    val model: String = "",
    val dateTime: String? = null,
    /** EXIF orientation (1 = upright, 6 = turn 90° clockwise to view, …). */
    val orientation: Int = 1,
    val exposureTimeS: Double? = null,
    val fNumber: Double? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null
) {
    companion object {
        const val SOFTWARE = "UltimateCamera"

        fun describe(method: String, render: String?): String = "$SOFTWARE hdr=$method" + (render?.let { " render=$it" } ?: "")
    }
}

/**
 * Writes JPEG metadata by inserting segments, never by rewriting the file: everything from the first segment after the
 * insertion point is copied byte for byte. Inserting before the APP2 MPF segment keeps an Ultra HDR file's gain map
 * reachable (MPF offsets count from the MPF header, which moves together with the gain-map image).
 */
object JpegMetadata {
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val COM = 0xFE
    private const val MAX_SEGMENT = 0xFFFF

    /** [input] (a JPEG with no EXIF, as `Bitmap.compress` writes) to [output] with an EXIF APP1 for [meta] first. */
    fun writeWithExif(input: InputStream, output: OutputStream, meta: PhotoMeta) {
        val data = DataInputStream(input.buffered())
        expectSoi(data)
        output.write(MARKER)
        output.write(SOI)
        output.write(exifSegment(meta))
        output.write(commentSegment(meta.description))
        data.copyTo(output)
    }

    /**
     * [input] (any JPEG, e.g. the maker's) to [output] with a comment holding [text], placed after the leading APP0/APP1
     * segments (its own EXIF stays first) and before everything else.
     */
    fun writeWithComment(input: InputStream, output: OutputStream, text: String) {
        val data = DataInputStream(input.buffered())
        expectSoi(data)
        output.write(MARKER)
        output.write(SOI)
        while (true) {
            val m0 = data.readUnsignedByte()
            val m1 = data.readUnsignedByte()
            if (m0 != MARKER) throw IOException("not a JPEG segment")
            if (m1 != APP0 && m1 != APP1) {
                output.write(commentSegment(text))
                output.write(m0)
                output.write(m1)
                data.copyTo(output)
                return
            }
            val length = data.readUnsignedShort()
            output.write(m0)
            output.write(m1)
            output.write(length shr 8)
            output.write(length and MARKER)
            val body = ByteArray(length - 2)
            data.readFully(body)
            output.write(body)
        }
    }

    private fun expectSoi(data: DataInputStream) {
        if (data.readUnsignedByte() != MARKER || data.readUnsignedByte() != SOI) throw IOException("not a JPEG")
    }

    fun commentSegment(text: String): ByteArray {
        val body = text.toByteArray(Charsets.US_ASCII).let { it.copyOf(minOf(it.size, MAX_SEGMENT - 2)) }
        return byteArrayOf(MARKER.toByte(), COM.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body
    }

    /** An APP1 `Exif\0\0` segment with a big-endian TIFF: IFD0 (description, make, model, orientation, software, date) and the Exif IFD. */
    fun exifSegment(meta: PhotoMeta): ByteArray {
        val ifd0 = buildList {
            add(Entry.ascii(TAG_DESCRIPTION, meta.description))
            if (meta.make.isNotEmpty()) add(Entry.ascii(TAG_MAKE, meta.make))
            if (meta.model.isNotEmpty()) add(Entry.ascii(TAG_MODEL, meta.model))
            add(Entry.short(TAG_ORIENTATION, meta.orientation))
            add(Entry.ascii(TAG_SOFTWARE, PhotoMeta.SOFTWARE))
            meta.dateTime?.let { add(Entry.ascii(TAG_DATETIME, it)) }
        }
        val exif = buildList {
            meta.exposureTimeS?.let { add(Entry.rational(TAG_EXPOSURE_TIME, it)) }
            meta.fNumber?.let { add(Entry.rational(TAG_F_NUMBER, it)) }
            meta.iso?.let { add(Entry.short(TAG_ISO, it.coerceIn(0, MAX_SEGMENT))) }
            meta.dateTime?.let { add(Entry.ascii(TAG_DATETIME_ORIGINAL, it)) }
            meta.focalLengthMm?.let { add(Entry.rational(TAG_FOCAL_LENGTH, it)) }
        }
        val tiff = Tiff()
        // IFD0 at 8; its size is known up front, the Exif IFD follows it.
        val ifd0Size = ifdSize(ifd0.size + 1)
        val ifd0Data = ifd0.sumOf { it.extra.size }
        val exifOffset = 8 + ifd0Size + ifd0Data
        tiff.header()
        tiff.ifd(8, ifd0 + Entry.long(TAG_EXIF_IFD, exifOffset))
        tiff.ifd(exifOffset, exif)
        val body = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) + tiff.bytes()
        return byteArrayOf(MARKER.toByte(), APP1.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body
    }

    private fun ifdSize(entries: Int) = 2 + entries * 12 + 4

    /** One IFD entry; [extra] holds the value when it does not fit in the 4-byte field. */
    private class Entry(val tag: Int, val type: Int, val count: Int, val inline: ByteArray, val extra: ByteArray) {
        companion object {
            fun ascii(tag: Int, text: String): Entry {
                val bytes = text.toByteArray(Charsets.US_ASCII) + 0
                return if (bytes.size <=
                    4
                ) {
                    Entry(tag, TYPE_ASCII, bytes.size, bytes.copyOf(4), ByteArray(0))
                } else {
                    Entry(tag, TYPE_ASCII, bytes.size, ByteArray(4), bytes)
                }
            }

            fun short(tag: Int, value: Int) = Entry(tag, TYPE_SHORT, 1, byteArrayOf((value shr 8).toByte(), value.toByte(), 0, 0), ByteArray(0))

            fun long(tag: Int, value: Int) = Entry(tag, TYPE_LONG, 1, ByteBuffer.allocate(4).putInt(value).array(), ByteArray(0))

            /** [value] as a fraction with denominator 1 000 000 (or 1/n for exposure times under a second). */
            fun rational(tag: Int, value: Double): Entry {
                val (num, den) = if (value in 1e-9..<1.0 && tag == TAG_EXPOSURE_TIME) {
                    1L to (1.0 / value).roundToLong()
                } else {
                    (value * RATIONAL_SCALE).roundToLong() to RATIONAL_SCALE.toLong()
                }
                val bytes = ByteBuffer.allocate(8).putInt(num.toInt()).putInt(den.toInt()).array()
                return Entry(tag, TYPE_RATIONAL, 1, ByteArray(4), bytes)
            }
        }
    }

    /** A growing big-endian TIFF. */
    private class Tiff {
        private val out = ByteArrayOutputStream()

        fun header() {
            out.write(byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8))
        }

        fun ifd(at: Int, entries: List<Entry>) {
            check(out.size() == at) { "IFD at $at, written ${out.size()}" }
            var data = at + ifdSize(entries.size)
            val sorted = entries.sortedBy { it.tag }
            out.write(ByteBuffer.allocate(2).putShort(sorted.size.toShort()).array())
            for (e in sorted) {
                val b = ByteBuffer.allocate(12).putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
                if (e.extra.isEmpty()) {
                    b.put(e.inline)
                } else {
                    b.putInt(data)
                    data += e.extra.size
                }
                out.write(b.array())
            }
            out.write(byteArrayOf(0, 0, 0, 0))
            sorted.forEach { out.write(it.extra) }
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    const val TAG_DESCRIPTION = 0x010E
    const val TAG_MAKE = 0x010F
    const val TAG_MODEL = 0x0110
    const val TAG_ORIENTATION = 0x0112
    const val TAG_SOFTWARE = 0x0131
    const val TAG_DATETIME = 0x0132
    const val TAG_EXIF_IFD = 0x8769
    const val TAG_EXPOSURE_TIME = 0x829A
    const val TAG_F_NUMBER = 0x829D
    const val TAG_ISO = 0x8827
    const val TAG_DATETIME_ORIGINAL = 0x9003
    const val TAG_FOCAL_LENGTH = 0x920A
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val RATIONAL_SCALE = 1_000_000
}
