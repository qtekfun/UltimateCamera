package com.qtekfun.ultimatecamera.core.gpu.style

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The style of a photo in its JPEG's XMP (D74), so it can be changed later: an APP1 XMP packet with
 * `uc:Style="<token>"` (namespace [NAMESPACE]) and, when an unstyled copy was kept, `uc:Base="<file name>"`.
 * Streaming: only the header segments are buffered, the image data is copied in chunks.
 */
object StyleXmp {
    const val NAMESPACE = "https://qtekfun.github.io/ultimatecamera/ns/style/1.0/"
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val MAX_SEGMENT = 0xFFFF - 2
    private const val CHUNK = 64 * 1024

    /** The unstyled copy's name next to [photo]: `UC_…_P001.jpg` → `UC_…_P001_base.jpg`. */
    fun baseName(photo: String): String = if ('.' in photo) photo.substringBeforeLast('.') + "_base." + photo.substringAfterLast('.') else photo + "_base"

    /** The XMP packet for [style] (and the unstyled copy's [base] name). */
    fun packet(style: StyleParams, base: String? = null): String = buildString {
        append("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\" xmlns:uc=\"").append(NAMESPACE).append("\" uc:Style=\"").append(escape(style.token)).append('"')
        if (base != null) append(" uc:Base=\"").append(escape(base)).append('"')
        append("/></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>")
    }

    /**
     * Copies the JPEG [input] to [output] with the XMP of [style] inserted after SOI, JFIF and EXIF. Returns false (and
     * still copies the file unchanged) when it already has an XMP packet, as Ultra HDR files do: their XMP holds the gain
     * map description and must stay the only one. Throws [IOException] when [input] is not a JPEG.
     */
    fun insert(input: InputStream, output: OutputStream, style: StyleParams, base: String? = null): Boolean {
        if (input.read() != 0xFF || input.read() != SOI) throw IOException("not a JPEG")
        output.write(0xFF)
        output.write(SOI)
        var inserted = false
        var done = false
        while (!done) {
            val marker = readMarker(input)
            if (marker == null) {
                done = true
            } else if (marker != APP0 && marker != APP1) {
                // The first segment that is not JFIF or EXIF (tables, frame, scan): ours goes just before it.
                writeXmp(output, style, base)
                output.write(0xFF)
                output.write(marker)
                inserted = true
                done = true
            } else {
                val segment = readSegment(input)
                output.write(0xFF)
                output.write(marker)
                output.write(segment.size + 2 shr 8)
                output.write(segment.size + 2 and 0xFF)
                output.write(segment)
                done = marker == APP1 && segment.startsWith(XMP_HEADER)
            }
        }
        input.copyTo(output, CHUNK)
        return inserted
    }

    /** The style token in [jpeg]'s XMP, or null. Reads the header segments only. */
    fun read(jpeg: InputStream): StyleParams? {
        if (jpeg.read() != 0xFF || jpeg.read() != SOI) return null
        while (true) {
            val marker = readMarker(jpeg) ?: return null
            if (marker == SOS) return null
            val segment = try {
                readSegment(jpeg)
            } catch (_: IOException) {
                return null
            }
            if (marker == APP1 && segment.startsWith(XMP_HEADER)) {
                val text = String(segment, Charsets.UTF_8)
                val value = text.substringAfter("uc:Style=\"", "").substringBefore('"')
                return StyleParams.parse(unescape(value))
            }
        }
    }

    private fun writeXmp(output: OutputStream, style: StyleParams, base: String?) {
        val body = (XMP_HEADER + packet(style, base)).toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_SEGMENT) { "XMP too large" }
        output.write(0xFF)
        output.write(APP1)
        output.write(body.size + 2 shr 8)
        output.write(body.size + 2 and 0xFF)
        output.write(body)
    }

    private fun readMarker(input: InputStream): Int? {
        var b = input.read()
        if (b != 0xFF) return null
        while (b == 0xFF) b = input.read()
        return if (b < 0) null else b
    }

    private fun readSegment(input: InputStream): ByteArray {
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) throw IOException("truncated JPEG")
        val length = (hi shl 8 or lo) - 2
        if (length < 0) throw IOException("bad JPEG segment")
        val data = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(data, n, length - n)
            if (r < 0) throw IOException("truncated JPEG")
            n += r
        }
        return data
    }

    private fun ByteArray.startsWith(prefix: String): Boolean {
        val p = prefix.toByteArray(Charsets.ISO_8859_1)
        return size >= p.size && p.indices.all { this[it] == p[it] }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&quot;", "\"").replace("&amp;", "&")
}
