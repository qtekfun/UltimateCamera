package com.qtekfun.ultimatecamera.core.codec.mp4

/** The QuickTime flavour of MediaMuxer's boxes for `.mov` takes (D69). */
internal object QuickTimeBoxes {
    private const val VISUAL_ENTRY_FIELDS = 78
    private const val STSD_PREFIX = 8
    private const val HDLR_NAME = 32
    private const val NCLX_SIZE = 19
    private val VISUAL_ENTRIES = setOf("avc1", "avc3", "hvc1", "hev1", "av01", "mp4v", "dvh1", "dvhe", "vp09")
    private const val QT_BRAND = "qt  "
    private const val QT_MINOR = 0x0000_0200L

    /** QuickTime media: `mhlr` handler with a Pascal name, `dhlr` in `minf`, `colr nclc` in the video sample entry. */
    fun mdia(file: SeekableFile, mdiaKids: List<BoxHeader>, handler: String, hdlr: ByteArray): Node = ContainerNode(
        "mdia",
        ByteArray(0),
        mdiaKids.map { k ->
            when (k.type) {
                "hdlr" -> BytesNode(handler("mhlr", handler, handlerName(hdlr)))
                "minf" -> qtMinf(file, k)
                else -> CopyNode(k)
            }
        }
    )

    private fun qtMinf(file: SeekableFile, minf: BoxHeader): Node {
        val kids = BoxReader.children(file, minf)
        val children = buildList {
            for (k in kids) {
                if (k.type == "dinf" && kids.none { it.type == "hdlr" }) add(BytesNode(handler("dhlr", "alis", "DataHandler")))
                add(if (k.type == "stbl") qtStbl(file, k) else CopyNode(k))
            }
        }
        return ContainerNode("minf", ByteArray(0), children)
    }

    private fun qtStbl(file: SeekableFile, stbl: BoxHeader): Node = ContainerNode(
        "stbl",
        ByteArray(0),
        BoxReader.children(file, stbl).map { k -> if (k.type == "stsd") qtStsd(file, k) else CopyNode(k) }
    )

    private fun qtStsd(file: SeekableFile, stsd: BoxHeader): Node {
        val prefix = ByteArray(STSD_PREFIX).also { file.read(stsd.payloadOffset, it) }
        val entries = BoxReader.children(file, stsd, STSD_PREFIX).map { entry ->
            if (entry.type in VISUAL_ENTRIES && entry.size >= entry.headerSize + VISUAL_ENTRY_FIELDS) qtVisualEntry(file, entry) else CopyNode(entry)
        }
        return ContainerNode("stsd", prefix, entries)
    }

    private fun qtVisualEntry(file: SeekableFile, entry: BoxHeader): Node {
        val kids = try {
            BoxReader.children(file, entry, VISUAL_ENTRY_FIELDS)
        } catch (_: UnexpectedFileException) {
            return CopyNode(entry)
        }
        val fields = ByteArray(VISUAL_ENTRY_FIELDS).also { file.read(entry.payloadOffset, it) }
        return ContainerNode(entry.type, fields, kids.map { k -> if (k.type == "colr") nclc(file, k) else CopyNode(k) })
    }

    /** ISO `nclx` (with the full-range flag) to QuickTime `nclc` (primaries, transfer, matrix). */
    private fun nclc(file: SeekableFile, colr: BoxHeader): Node {
        if (colr.size != NCLX_SIZE.toLong()) return CopyNode(colr)
        val b = BoxReader.bytes(file, colr)
        if (Bytes.fourcc(b, 8) != "nclx") return CopyNode(colr)
        return BytesNode(
            box("colr") {
                fourcc("nclc")
                write(b, 12, 6)
            }
        )
    }

    private fun handlerName(hdlr: ByteArray): String {
        if (hdlr.size <= HDLR_NAME) return ""
        val raw = hdlr.copyOfRange(HDLR_NAME, hdlr.size)
        // Already a Pascal string (QuickTime) or a C string (ISO, MediaMuxer).
        val text = if (raw[0].toInt() == raw.size - 1) raw.copyOfRange(1, raw.size) else raw
        return String(text, Charsets.ISO_8859_1).trimEnd('\u0000')
    }

    fun handler(component: String, subtype: String, name: String): ByteArray = box("hdlr") {
        writeInt(0)
        fourcc(component)
        fourcc(subtype)
        writeInt(0)
        writeInt(0)
        writeInt(0)
        val text = name.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray()
        writeByte(text.size)
        write(text)
    }

    /** The `ftyp` payload rewritten as `qt  ` (minor 0x200) in the same size. */
    fun brands(ftyp: BoxHeader): ByteArray {
        val payload = ByteArray((ftyp.size - ftyp.headerSize).toInt())
        Bytes.fourccBytes(QT_BRAND).copyInto(payload, 0)
        Bytes.putU32(payload, 4, QT_MINOR)
        var at = 8
        while (at + 4 <= payload.size) {
            Bytes.fourccBytes(QT_BRAND).copyInto(payload, at)
            at += 4
        }
        return payload
    }
}
