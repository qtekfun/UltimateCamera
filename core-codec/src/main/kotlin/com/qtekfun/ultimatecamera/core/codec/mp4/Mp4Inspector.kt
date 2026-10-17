package com.qtekfun.ultimatecamera.core.codec.mp4

/** One track as [Mp4Inspector] sees it. */
data class TrackSummary(
    val trackId: Long,
    val handler: String,
    /** `hdlr` component type: `mhlr` in QuickTime movies, empty (zero) in ISO files. */
    val component: String,
    /** Track id the `tref`/`tmcd` reference points to, or null. */
    val timecodeRef: Long?,
    /** `colr` colour type of the first sample entry (`nclx`, `nclc`), or null. */
    val colourType: String?
)

/** What a take's file holds, read from box headers and small boxes only (tests, diagnostics). */
data class Mp4Summary(
    val majorBrand: String,
    val topLevel: List<String>,
    val tracks: List<TrackSummary>,
    /** The `tmcd` track's start (its first sample and sample description), or null. */
    val timecode: TimecodeStart?,
    /** `moov/udta/©day`, or null. */
    val creationDay: String?,
    val nextTrackId: Long
)

/** Reads an [Mp4Summary]; throws [UnexpectedFileException] (an IOException) on a malformed file. */
object Mp4Inspector {
    private const val HDLR_COMPONENT = 12
    private const val HDLR_SUBTYPE = 16
    private const val VISUAL_ENTRY_FIELDS = 78

    fun inspect(file: SeekableFile): Mp4Summary {
        val top = BoxReader.children(file, 0, file.size())
        val ftyp = top.first().also { if (it.type != "ftyp") BoxReader.unexpected("no ftyp") }
        val brand = ByteArray(4).also { file.read(ftyp.payloadOffset, it) }
        val moov = top.single { it.type == "moov" }
        val kids = BoxReader.children(file, moov)
        val mvhd = BoxReader.bytes(file, kids.single { it.type == "mvhd" })
        var timecode: TimecodeStart? = null
        val tracks = kids.filter { it.type == "trak" }.map { trak ->
            val tk = BoxReader.children(file, trak)
            val tkhd = BoxReader.bytes(file, tk.single { it.type == "tkhd" })
            val id = Bytes.u32(tkhd, if (tkhd[8].toInt() != 0) 28 else 20)
            val ref = tk.firstOrNull { it.type == "tref" }?.let { tref ->
                BoxReader.children(file, tref).firstOrNull { it.type == "tmcd" }?.let { Bytes.u32(BoxReader.bytes(file, it), 8) }
            }
            val mdia = BoxReader.children(file, tk.single { it.type == "mdia" })
            val hdlr = BoxReader.bytes(file, mdia.single { it.type == "hdlr" })
            val handler = Bytes.fourcc(hdlr, HDLR_SUBTYPE)
            val stbl = find(file, mdia, "minf", "stbl")
            val stsd = stbl?.firstOrNull { it.type == "stsd" }
            val entry = stsd?.let { BoxReader.children(file, it, 8).firstOrNull() }
            if (handler == "tmcd" && entry != null && stbl != null) timecode = readTimecode(file, entry, stbl)
            val colour = entry?.takeIf { handler == "vide" }?.let { e ->
                BoxReader.children(file, e, VISUAL_ENTRY_FIELDS).firstOrNull { it.type == "colr" }?.let { Bytes.fourcc(BoxReader.bytes(file, it), 8) }
            }
            val component = Bytes.fourcc(hdlr, HDLR_COMPONENT).takeIf { Bytes.u32(hdlr, HDLR_COMPONENT) != 0L }.orEmpty()
            TrackSummary(id, handler, component, ref, colour)
        }
        val day = kids.firstOrNull { it.type == "udta" }?.let { udta ->
            BoxReader.children(file, udta).firstOrNull { it.type == Mp4PostProcessor.DAY_TYPE }?.let {
                val b = BoxReader.bytes(file, it)
                String(b, 12, Bytes.u16(b, 8), Charsets.US_ASCII)
            }
        }
        return Mp4Summary(String(brand, Charsets.ISO_8859_1), top.map { it.type }, tracks, timecode, day, Bytes.u32(mvhd, mvhd.size - 4))
    }

    private fun find(file: SeekableFile, kids: List<BoxHeader>, vararg path: String): List<BoxHeader>? {
        var level = kids
        for (type in path) level = BoxReader.children(file, level.firstOrNull { it.type == type } ?: return null)
        return level
    }

    private fun readTimecode(file: SeekableFile, entry: BoxHeader, stbl: List<BoxHeader>): TimecodeStart {
        val e = BoxReader.bytes(file, entry)
        val flags = Bytes.u32(e, 20).toInt()
        val scale = Bytes.u32(e, 24).toInt()
        val duration = Bytes.u32(e, 28).toInt()
        val co = stbl.first { it.type == "stco" || it.type == "co64" }
        val c = BoxReader.bytes(file, co)
        val offset = if (co.type == "co64") Bytes.u64(c, 16) else Bytes.u32(c, 16)
        val sample = ByteArray(4).also { file.read(offset, it) }
        return TimecodeStart(Bytes.u32(sample, 0), scale, duration, dropFrame = flags and 1 != 0)
    }
}
