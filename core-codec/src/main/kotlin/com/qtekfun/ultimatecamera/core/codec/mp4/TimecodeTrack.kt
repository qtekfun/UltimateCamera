package com.qtekfun.ultimatecamera.core.codec.mp4

/**
 * A QuickTime timecode track (D69): `tmcd` sample description (flags, timescale, frame duration, frames per second)
 * and one 4-byte sample, the start frame counter, stored at [trak]'s `sampleOffset`.
 */
internal object TimecodeTrack {
    private const val TMCD_FLAG_DROP = 0x1
    private const val TMCD_FLAG_24H = 0x2
    private const val TRACK_ENABLED_IN_MOVIE = 0x3
    private const val GRAPHICS_MODE_DITHER_COPY = 0x40
    private const val OPCOLOR = 0x8000
    private const val TEXT_SIZE = 12
    private val IDENTITY = intArrayOf(0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000)

    private fun isoHandler(subtype: String, name: String): ByteArray = box("hdlr") {
        writeInt(0)
        writeInt(0)
        fourcc(subtype)
        writeInt(0)
        writeInt(0)
        writeInt(0)
        write(name.toByteArray(Charsets.ISO_8859_1))
        writeByte(0)
    }

    fun trak(tc: TimecodeStart, id: Long, duration: Long, mvhd: ByteArray, sampleOffset: Long, quickTime: Boolean): ByteArray {
        val movieV1 = mvhd[8].toInt() != 0
        val movieScale = Bytes.u32(mvhd, if (movieV1) 28 else 20)
        if (movieScale <= 0) BoxReader.unexpected("movie timescale 0")
        val mediaDuration = maxOf(duration * tc.numerator / movieScale, tc.denominator.toLong())
        if (mediaDuration > Mp4PostProcessor.MAX_U32 || duration > Mp4PostProcessor.MAX_U32) BoxReader.unexpected("take too long for a 32-bit timecode track")
        val creation = if (movieV1) 0L else Bytes.u32(mvhd, 12)
        return box("trak") {
            write(
                box("tkhd") {
                    writeInt(TRACK_ENABLED_IN_MOVIE)
                    writeInt(creation.toInt())
                    writeInt(creation.toInt())
                    writeInt(id.toInt())
                    writeInt(0)
                    writeInt(duration.toInt())
                    repeat(2) { writeInt(0) }
                    writeShort(0) // layer
                    writeShort(0) // alternate group
                    writeShort(0) // volume
                    writeShort(0)
                    IDENTITY.forEach(::writeInt)
                    writeInt(0)
                    writeInt(0)
                }
            )
            write(
                box("mdia") {
                    write(
                        box("mdhd") {
                            writeInt(0)
                            writeInt(creation.toInt())
                            writeInt(creation.toInt())
                            writeInt(tc.numerator)
                            writeInt(mediaDuration.toInt())
                            writeShort(Mp4PostProcessor.LANGUAGE_UND)
                            writeShort(0)
                        }
                    )
                    write(if (quickTime) QuickTimeBoxes.handler("mhlr", "tmcd", "TimeCodeHandler") else isoHandler("tmcd", "TimeCodeHandler"))
                    write(tmcdMinf(tc, mediaDuration, sampleOffset, quickTime))
                }
            )
        }
    }

    private fun tmcdMinf(tc: TimecodeStart, mediaDuration: Long, sampleOffset: Long, quickTime: Boolean): ByteArray = box("minf") {
        write(
            box("gmhd") {
                write(
                    box("gmin") {
                        writeInt(0)
                        writeShort(GRAPHICS_MODE_DITHER_COPY)
                        repeat(3) { writeShort(OPCOLOR) }
                        writeShort(0)
                        writeShort(0)
                    }
                )
                write(
                    box("tmcd") {
                        write(
                            box("tcmi") {
                                writeInt(0)
                                writeShort(0) // font
                                writeShort(0) // face
                                writeShort(TEXT_SIZE)
                                writeShort(0)
                                repeat(3) { writeShort(0) } // text colour
                                repeat(3) { writeShort(0xFFFF) } // background
                                writeByte(0) // font name (Pascal, empty)
                            }
                        )
                    }
                )
            }
        )
        if (quickTime) write(QuickTimeBoxes.handler("dhlr", "alis", "DataHandler"))
        write(
            box("dinf") {
                write(
                    box("dref") {
                        writeInt(0)
                        writeInt(1)
                        write(box(if (quickTime) "alis" else "url ") { writeInt(1) })
                    }
                )
            }
        )
        write(tmcdStbl(tc, mediaDuration, sampleOffset))
    }

    private fun tmcdStbl(tc: TimecodeStart, mediaDuration: Long, sampleOffset: Long): ByteArray = box("stbl") {
        write(
            box("stsd") {
                writeInt(0)
                writeInt(1)
                write(
                    box("tmcd") {
                        writeInt(0) // reserved
                        writeShort(0) // reserved
                        writeShort(1) // data reference index
                        writeInt(0) // reserved
                        writeInt((if (tc.dropFrame) TMCD_FLAG_DROP else 0) or TMCD_FLAG_24H)
                        writeInt(tc.numerator)
                        writeInt(tc.denominator)
                        writeByte(tc.nominal)
                        writeByte(0)
                    }
                )
            }
        )
        write(
            box("stts") {
                writeInt(0)
                writeInt(1)
                writeInt(1)
                writeInt(mediaDuration.toInt())
            }
        )
        write(
            box("stsc") {
                writeInt(0)
                writeInt(1)
                writeInt(1)
                writeInt(1)
                writeInt(1)
            }
        )
        write(
            box("stsz") {
                writeInt(0)
                writeInt(4)
                writeInt(1)
            }
        )
        if (sampleOffset + 4 <= Mp4PostProcessor.MAX_U32) {
            write(
                box("stco") {
                    writeInt(0)
                    writeInt(1)
                    writeInt(sampleOffset.toInt())
                }
            )
        } else {
            write(
                box("co64") {
                    writeInt(0)
                    writeInt(1)
                    writeLong(sampleOffset)
                }
            )
        }
    }
}
