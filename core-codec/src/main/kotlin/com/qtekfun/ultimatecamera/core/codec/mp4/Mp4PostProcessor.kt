package com.qtekfun.ultimatecamera.core.codec.mp4

import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What [Mp4PostProcessor] adds to a closed take (D69).
 *
 * @property timecode start of a QuickTime `tmcd` track referenced by the video track; null = no track.
 * @property creationEpochSeconds take start, written as the movie's creation time and a `©day` entry in `udta`.
 * @property quickTime turn the file into a QuickTime movie (`ftyp qt  `, `mhlr`/`dhlr` handlers, `colr nclc`); the
 *   caller names it `.mov`.
 */
data class PostProcessOptions(val timecode: TimecodeStart? = null, val creationEpochSeconds: Long? = null, val quickTime: Boolean = false) {
    val isEmpty: Boolean get() = timecode == null && creationEpochSeconds == null && !quickTime
}

/** Outcome of [Mp4PostProcessor.process]. */
sealed interface PostProcessResult {
    /** The new `moov` is in place; [addedBytes] were appended. */
    data class Done(val addedBytes: Long) : PostProcessResult

    /** Nothing was written: the file is not what MediaMuxer makes ([reason]) or there was nothing to do. */
    data class Skipped(val reason: String) : PostProcessResult

    /** Writing failed; what was appended was cut off again, so the file is MediaMuxer's original. */
    data class Failed(val reason: String) : PostProcessResult
}

/**
 * Post-processes a closed MediaMuxer MP4 in place, without moving `mdat` (D69): the new `moov` (plus a 12-byte `mdat`
 * holding the timecode sample) is appended at the end, synced, and only then is the old `moov` turned into `free` by an
 * 8-byte header write, the last write. Chunk offsets therefore never change. Until that write the original `moov` comes
 * first and players use it; a failure while appending truncates the file back. Leaf tables (sample sizes, offsets…) are
 * copied by range through a 64 KiB buffer; only headers and sample descriptions are read into memory.
 */
object Mp4PostProcessor {
    private const val MAC_EPOCH_OFFSET = 2_082_844_800L
    internal const val MAX_U32 = 0xFFFF_FFFFL
    internal const val LANGUAGE_UND = 0x55C4
    private const val HDLR_SUBTYPE = 16
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun process(file: SeekableFile, options: PostProcessOptions): PostProcessResult {
        if (options.isEmpty) return PostProcessResult.Skipped("nothing to do")
        val appendAt: Long
        val plan = try {
            appendAt = file.size()
            plan(file, options, appendAt)
        } catch (e: IOException) {
            return PostProcessResult.Skipped(e.message ?: e.javaClass.simpleName)
        }
        try {
            val writer = BoxWriter(file, file, appendAt)
            plan.prelude?.let { writer.write(BytesNode(it)) }
            writer.write(plan.moov)
            file.sync()
        } catch (e: IOException) {
            runCatching {
                file.truncate(appendAt)
                file.sync()
            }
            return PostProcessResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        val added = file.size() - appendAt
        return try {
            if (options.quickTime) file.write(plan.ftyp.payloadOffset, QuickTimeBoxes.brands(plan.ftyp))
            // The switch: from here on the appended moov is the only one.
            val free = ByteArray(8)
            file.read(plan.oldMoov.offset, free, 0, 4)
            Bytes.fourccBytes("free").copyInto(free, 4)
            file.write(plan.oldMoov.offset, free)
            file.sync()
            PostProcessResult.Done(added)
        } catch (e: IOException) {
            // The old moov still comes first: the file plays as MediaMuxer wrote it.
            PostProcessResult.Failed("switch: ${e.message}")
        }
    }

    private class Plan(val ftyp: BoxHeader, val oldMoov: BoxHeader, val prelude: ByteArray?, val moov: Node)

    private class Trak(
        val box: BoxHeader,
        val kids: List<BoxHeader>,
        val tkhd: ByteArray,
        val mdia: BoxHeader,
        val mdiaKids: List<BoxHeader>,
        val hdlr: ByteArray
    ) {
        val v1: Boolean get() = tkhd[8].toInt() != 0
        val trackId: Long get() = Bytes.u32(tkhd, if (v1) 28 else 20)
        val duration: Long get() = if (v1) Bytes.u64(tkhd, 36) else Bytes.u32(tkhd, 28)
        val handler: String get() = Bytes.fourcc(hdlr, HDLR_SUBTYPE)
    }

    private fun plan(file: SeekableFile, options: PostProcessOptions, appendAt: Long): Plan {
        val top = BoxReader.children(file, 0, appendAt)
        val ftyp = top.firstOrNull()?.takeIf { it.type == "ftyp" } ?: BoxReader.unexpected("the file does not start with ftyp")
        val moov = top.singleOrNull { it.type == "moov" } ?: BoxReader.unexpected("expected one moov, found ${top.count { it.type == "moov" }}")
        if (top.none { it.type == "mdat" }) BoxReader.unexpected("no mdat")
        if (options.quickTime && ftyp.size < 16) BoxReader.unexpected("ftyp too small")
        val kids = BoxReader.children(file, moov)
        val mvhdBox = kids.singleOrNull { it.type == "mvhd" } ?: BoxReader.unexpected("no mvhd")
        val traks = kids.filter { it.type == "trak" }.map { readTrak(file, it) }
        val video = traks.firstOrNull { it.handler == "vide" } ?: BoxReader.unexpected("no video track")
        if (traks.any { it.handler == "tmcd" }) BoxReader.unexpected("already has a timecode track")
        val mvhd = BoxReader.bytes(file, mvhdBox)
        val tc = options.timecode
        val tmcdId = maxOf(Bytes.u32(mvhd, mvhd.size - 4), (traks.maxOfOrNull { it.trackId } ?: 0) + 1)
        val newMvhd = patchMvhd(mvhd, if (tc != null) tmcdId + 1 else null, options.creationEpochSeconds)
        val sampleOffset = appendAt + 8
        val tmcdTrak = tc?.let { TimecodeTrack.trak(it, tmcdId, video.duration, mvhd, sampleOffset, options.quickTime) }
        val videoTrak = video.takeIf { tc != null }
        val children = moovChildren(file, kids, traks, MoovEdit(BytesNode(newMvhd), videoTrak, tmcdId, tmcdTrak, options))
        val prelude = tc?.let { start -> box("mdat") { writeInt(start.frame.toInt()) } }
        return Plan(ftyp, moov, prelude, ContainerNode("moov", ByteArray(0), children))
    }

    private class MoovEdit(val mvhd: Node, val video: Trak?, val tmcdId: Long, val tmcdTrak: ByteArray?, val options: PostProcessOptions)

    private fun moovChildren(file: SeekableFile, kids: List<BoxHeader>, traks: List<Trak>, edit: MoovEdit): List<Node> = buildList {
        val lastTrak = kids.last { it.type == "trak" }
        val creation = edit.options.creationEpochSeconds
        for (k in kids) {
            when (k.type) {
                "mvhd" -> add(edit.mvhd)
                "trak" -> add(traks.first { it.box === k }.let { trakNode(file, it, edit.tmcdId.takeIf { _ -> it === edit.video }, edit.options.quickTime) })
                "udta" -> add(creation?.let { udta(file, k, it) } ?: CopyNode(k))
                "iods" -> if (!edit.options.quickTime) add(CopyNode(k))
                else -> add(CopyNode(k))
            }
            if (k === lastTrak) edit.tmcdTrak?.let { add(BytesNode(it)) }
        }
        if (kids.none { it.type == "udta" }) creation?.let { add(BytesNode(box("udta") { write(dayBox(it)) })) }
    }

    private fun readTrak(file: SeekableFile, box: BoxHeader): Trak {
        val kids = BoxReader.children(file, box)
        val tkhd = kids.firstOrNull { it.type == "tkhd" } ?: BoxReader.unexpected("trak without tkhd")
        val mdia = kids.firstOrNull { it.type == "mdia" } ?: BoxReader.unexpected("trak without mdia")
        val mdiaKids = BoxReader.children(file, mdia)
        val hdlr = mdiaKids.firstOrNull { it.type == "hdlr" } ?: BoxReader.unexpected("mdia without hdlr")
        return Trak(box, kids, BoxReader.bytes(file, tkhd), mdia, mdiaKids, BoxReader.bytes(file, hdlr))
    }

    private fun patchMvhd(mvhd: ByteArray, nextTrackId: Long?, creation: Long?): ByteArray {
        val out = mvhd.copyOf()
        nextTrackId?.let { Bytes.putU32(out, out.size - 4, it) }
        creation?.let {
            val mac = it + MAC_EPOCH_OFFSET
            if (out[8].toInt() == 0) {
                if (mac in 0..MAX_U32) {
                    Bytes.putU32(out, 12, mac)
                    Bytes.putU32(out, 16, mac)
                }
            } else {
                Bytes.putU64(out, 12, mac)
                Bytes.putU64(out, 20, mac)
            }
        }
        return out
    }

    private fun trakNode(file: SeekableFile, trak: Trak, tmcdRef: Long?, quickTime: Boolean): Node {
        if (tmcdRef == null && !quickTime) return CopyNode(trak.box)
        val tmcdEntry = tmcdRef?.let { id -> box("tmcd") { writeInt(id.toInt()) } }
        val children = buildList {
            for (k in trak.kids) {
                when {
                    k.type == "tref" && tmcdEntry != null -> add(
                        ContainerNode(
                            "tref",
                            ByteArray(0),
                            BoxReader.children(file, k).map(::CopyNode) + BytesNode(tmcdEntry)
                        )
                    )
                    k.type == "mdia" && quickTime -> add(QuickTimeBoxes.mdia(file, trak.mdiaKids, trak.handler, trak.hdlr))
                    else -> add(CopyNode(k))
                }
                if (k.type == "tkhd" && tmcdEntry != null && trak.kids.none { it.type == "tref" }) add(BytesNode(box("tref") { write(tmcdEntry) }))
            }
        }
        return ContainerNode("trak", ByteArray(0), children)
    }

    private fun udta(file: SeekableFile, udta: BoxHeader, creation: Long): Node {
        val kids = BoxReader.children(file, udta).filter { it.type != DAY_TYPE }.map(::CopyNode)
        return ContainerNode("udta", ByteArray(0), kids + BytesNode(dayBox(creation)))
    }

    /** QuickTime user data text: `©day` with a 16-bit length, a language code and the ISO 8601 date (UTC). */
    private fun dayBox(epochSeconds: Long): ByteArray = box(DAY_TYPE) {
        val text = DAY.format(Instant.ofEpochSecond(epochSeconds)).toByteArray(Charsets.US_ASCII)
        writeShort(text.size)
        writeShort(LANGUAGE_UND)
        write(text)
    }

    const val DAY_TYPE = "\u00A9day"
}
