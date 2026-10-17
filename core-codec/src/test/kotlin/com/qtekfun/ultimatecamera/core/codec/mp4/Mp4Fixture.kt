package com.qtekfun.ultimatecamera.core.codec.mp4

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Builds small but structurally real MediaMuxer-style MP4 files: `ftyp`, `mdat` with [samples] video samples (each
 * filled with its own index), and `moov` with one HEVC video track (`hvc1` + `hvcC` + `colr nclx`), optionally an AAC
 * track, at the end ([moovFirst] = false) or at the front in MediaMuxer's reserved space (followed by `free`).
 */
internal object Mp4Fixture {
    const val SAMPLE_SIZE = 100
    const val MOVIE_TIMESCALE = 1000
    const val DURATION = 3000

    fun write(file: File, samples: Int = 3, moovFirst: Boolean = false, audio: Boolean = false): File {
        val ftyp = box("ftyp") {
            fourcc("mp42")
            writeInt(0)
            fourcc("isom")
            fourcc("mp42")
        }
        val mdatPayload = ByteArray(samples * SAMPLE_SIZE) { (it / SAMPLE_SIZE).toByte() }
        val reserved = 4096
        // MediaMuxer layout: ftyp, [moov + free in the reserved space], mdat, [moov].
        val mdatOffset = if (moovFirst) ftyp.size + reserved else ftyp.size
        val moov = moov(samples, mdatOffset.toLong() + 8, audio)
        val out = ByteArrayOutputStream()
        out.write(ftyp)
        if (moovFirst) {
            out.write(moov)
            out.write(box("free") { write(ByteArray(reserved - moov.size - 8)) })
        }
        out.write(box("mdat") { write(mdatPayload) })
        if (!moovFirst) out.write(moov)
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun moov(samples: Int, firstSample: Long, audio: Boolean) = box("moov") {
        write(
            box("mvhd") {
                writeInt(0)
                writeInt(1000)
                writeInt(1000)
                writeInt(MOVIE_TIMESCALE)
                writeInt(DURATION)
                writeInt(0x0001_0000)
                writeShort(0x0100)
                write(ByteArray(10))
                intArrayOf(0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000).forEach(::writeInt)
                write(ByteArray(24))
                writeInt(if (audio) 3 else 2)
            }
        )
        write(trak(1, "vide", "VideoHandle", samples, firstSample, video = true))
        if (audio) write(trak(2, "soun", "SoundHandle", 1, firstSample, video = false))
        write(box("meta") { write(ByteArray(12)) })
    }

    private fun trak(id: Int, handler: String, name: String, samples: Int, firstSample: Long, video: Boolean) = box("trak") {
        write(
            box("tkhd") {
                writeInt(if (video) 7 else 3)
                writeInt(1000)
                writeInt(1000)
                writeInt(id)
                writeInt(0)
                writeInt(DURATION)
                write(ByteArray(8))
                writeShort(0)
                writeShort(0)
                writeShort(if (video) 0 else 0x0100)
                writeShort(0)
                intArrayOf(0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000).forEach(::writeInt)
                writeInt(if (video) 1920 shl 16 else 0)
                writeInt(if (video) 1080 shl 16 else 0)
            }
        )
        write(
            box("mdia") {
                write(
                    box("mdhd") {
                        writeInt(0)
                        writeInt(1000)
                        writeInt(1000)
                        writeInt(90000)
                        writeInt(270000)
                        writeShort(0x55C4)
                        writeShort(0)
                    }
                )
                write(
                    box("hdlr") {
                        writeInt(0)
                        writeInt(0)
                        fourcc(handler)
                        write(ByteArray(12))
                        write(name.toByteArray())
                        writeByte(0)
                    }
                )
                write(minf(samples, firstSample, video))
            }
        )
    }

    private fun minf(samples: Int, firstSample: Long, video: Boolean) = box("minf") {
        write(if (video) box("vmhd") { write(ByteArray(12)) } else box("smhd") { write(ByteArray(8)) })
        write(
            box("dinf") {
                write(
                    box("dref") {
                        writeInt(0)
                        writeInt(1)
                        write(box("url ") { writeInt(1) })
                    }
                )
            }
        )
        write(
            box("stbl") {
                write(
                    box("stsd") {
                        writeInt(0)
                        writeInt(1)
                        write(if (video) hvc1() else box("mp4a") { write(ByteArray(28)) })
                    }
                )
                write(
                    box("stts") {
                        writeInt(0)
                        writeInt(1)
                        writeInt(samples)
                        writeInt(3000)
                    }
                )
                write(
                    box("stsz") {
                        writeInt(0)
                        writeInt(0)
                        writeInt(samples)
                        repeat(samples) { writeInt(SAMPLE_SIZE) }
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
                    box("stco") {
                        writeInt(0)
                        writeInt(samples)
                        repeat(samples) { writeInt((firstSample + it * SAMPLE_SIZE).toInt()) }
                    }
                )
            }
        )
    }

    private fun hvc1() = box("hvc1") {
        write(ByteArray(6))
        writeShort(1)
        write(ByteArray(16))
        writeShort(1920)
        writeShort(1080)
        writeInt(0x0048_0000)
        writeInt(0x0048_0000)
        writeInt(0)
        writeShort(1)
        write(ByteArray(32))
        writeShort(0x18)
        writeShort(0xFFFF)
        write(box("hvcC") { write(ByteArray(23)) })
        write(
            box("colr") {
                fourcc("nclx")
                writeShort(9)
                writeShort(18)
                writeShort(9)
                writeByte(0)
            }
        )
    }

    private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray = com.qtekfun.ultimatecamera.core.codec.mp4.box(type, body)

    fun open(file: File): Pair<RandomAccessFile, SeekableFile> {
        val raf = RandomAccessFile(file, "rw")
        return raf to ChannelSeekableFile(raf.channel)
    }
}
