package com.qtekfun.ultimatecamera.core.gpu.style

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The style of a photo in its JPEG's XMP (D79), so it can be changed later: an APP1 XMP packet with
 * `uc:Style="<token>"` (namespace [NAMESPACE]) and, when an unstyled copy was kept, `uc:Base="<file name>"`.
 * Streaming: only the header segments are buffered, the image data is copied in chunks.
 */
object StyleXmp {
    const val NAMESPACE = "https://qtekfun.github.io/ultimatecamera/ns/style/1.0/"

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
    fun read(jpeg: InputStream): StyleParams? = inspect(jpeg).style

    /**
     * What [jpeg]'s XMP says (D79): the recorded style, the unstyled copy's name and whether the file carries an XMP
     * that is not ours (an Ultra HDR gain map description). Reads the header segments only; not a JPEG reads as [Info.NONE].
     */
    fun inspect(jpeg: InputStream): Info {
        if (jpeg.read() != 0xFF || jpeg.read() != SOI) return Info.NONE
        while (true) {
            val marker = readMarker(jpeg) ?: return Info.NONE
            if (marker == SOS) return Info.NONE
            val segment = try {
                readSegment(jpeg)
            } catch (_: IOException) {
                return Info.NONE
            }
            if (marker == APP1 && segment.startsWith(XMP_HEADER)) {
                val text = String(segment, Charsets.UTF_8)
                val style = attribute(text, "uc:Style")?.let { StyleParams.parse(it) }
                return Info(style, attribute(text, "uc:Base")?.takeIf { it.isNotBlank() }, foreignXmp = NAMESPACE !in text)
            }
        }
    }

    /** What [inspect] found; [NONE] for a file without any XMP. */
    data class Info(val style: StyleParams?, val base: String?, val foreignXmp: Boolean) {
        companion object {
            val NONE = Info(style = null, base = null, foreignXmp = false)
        }
    }

    /**
     * A restyled photo (D79 follow-up): the header of [original] (its EXIF APP1 and JPEG comments, everything a viewer
     * shows about the shot) on the freshly [encoded] pixels, with [style]'s XMP in place of the old one. [encoded]'s own
     * JFIF, ICC and other segments are kept, any EXIF or style XMP it has is dropped. When [encoded] carries a foreign
     * XMP (a gain map), that one stays and no style XMP is written (returns false), as in [insert]. Streaming: only the
     * header segments are buffered. Throws [IOException] when either input is not a JPEG.
     */
    fun restyle(original: InputStream, encoded: InputStream, output: OutputStream, style: StyleParams, base: String? = null): Boolean {
        val kept = headerSegments(original).filter { (marker, data) -> (marker == APP1 && data.startsWith(EXIF_HEADER)) || marker == COM }
        val (fresh, next) = headerRun(encoded)
        val own = fresh.filterNot { (marker, data) -> marker == APP1 && (data.startsWith(EXIF_HEADER) || ours(data)) }
        val foreign = own.any { (marker, data) -> marker == APP1 && data.startsWith(XMP_HEADER) }
        output.write(0xFF)
        output.write(SOI)
        own.filter { it.first == APP0 }.forEach { writeSegment(output, it) }
        kept.filter { it.first == APP1 }.forEach { writeSegment(output, it) }
        if (!foreign) writeXmp(output, style, base)
        own.filter { it.first != APP0 }.forEach { writeSegment(output, it) }
        kept.filter { it.first == COM }.forEach { writeSegment(output, it) }
        if (next != null) {
            output.write(0xFF)
            output.write(next)
        }
        encoded.copyTo(output, CHUNK)
        return !foreign
    }

    /**
     * The name of a restyled copy of [photo] that is not in [taken]: `UC_…_P001.jpg` → `UC_…_P001_s1.jpg`, then `_s2`…
     * A copy of a copy numbers on from the same photo (`…_P001_s1.jpg` → `…_P001_s2.jpg`).
     */
    fun restyledName(photo: String, taken: Set<String>): String {
        val dot = photo.lastIndexOf('.')
        val stem = if (dot > 0) photo.substring(0, dot) else photo
        val extension = if (dot > 0) photo.substring(dot) else ""
        val root = stem.replace(RESTYLED_SUFFIX, "")
        var n = 1
        while ("${root}_s$n$extension" in taken) n++
        return "${root}_s$n$extension"
    }
}

private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
private const val SOI = 0xD8
private const val SOS = 0xDA
private const val APP0 = 0xE0
private const val APP1 = 0xE1
private const val APP15 = 0xEF
private const val COM = 0xFE
private const val EXIF_HEADER = "Exif\u0000\u0000"
private const val MAX_HEADER_SEGMENTS = 64
private const val MAX_SEGMENT = 0xFFFF - 2
private const val CHUNK = 64 * 1024

private val RESTYLED_SUFFIX = Regex("_s\\d+$")

private fun attribute(text: String, name: String): String? {
    val start = text.indexOf("$name=\"")
    if (start < 0) return null
    val from = start + name.length + 2
    val end = text.indexOf('"', from)
    return if (end < 0) null else unescape(text.substring(from, end))
}

private fun ours(data: ByteArray): Boolean = data.startsWith(XMP_HEADER) && StyleXmp.NAMESPACE in String(data, Charsets.UTF_8)

/** The APPn and COM segments after SOI, up to the first other marker (consumed, returned as the second value). */
private fun headerRun(input: InputStream): Pair<List<Pair<Int, ByteArray>>, Int?> {
    if (input.read() != 0xFF || input.read() != SOI) throw IOException("not a JPEG")
    val segments = ArrayList<Pair<Int, ByteArray>>()
    while (true) {
        val marker = readMarker(input) ?: return segments to null
        if (marker !in APP0..APP15 && marker != COM) return segments to marker
        if (segments.size >= MAX_HEADER_SEGMENTS) throw IOException("too many JPEG header segments")
        segments += marker to readSegment(input)
    }
}

private fun headerSegments(input: InputStream): List<Pair<Int, ByteArray>> = headerRun(input).first

private fun writeSegment(output: OutputStream, segment: Pair<Int, ByteArray>) {
    val (marker, data) = segment
    output.write(0xFF)
    output.write(marker)
    output.write(data.size + 2 shr 8)
    output.write(data.size + 2 and 0xFF)
    output.write(data)
}

private fun writeXmp(output: OutputStream, style: StyleParams, base: String?) {
    val body = (XMP_HEADER + StyleXmp.packet(style, base)).toByteArray(Charsets.UTF_8)
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
