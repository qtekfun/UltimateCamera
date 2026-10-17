package com.qtekfun.ultimatecamera.core.recording

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegMetadataTest {
    private fun seg(marker: Int, body: ByteArray) =
        byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body

    /** A fake Ultra HDR file: SOI, APP0, APP2 "MPF" pointing 10 bytes past its header, scan data, EOI, gain-map image. */
    private val ultraHdr = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg(0xE0, "JFIF\u0000".toByteArray()) +
        seg(0xE2, "MPF\u0000offset=10".toByteArray()) + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()) +
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9, 0xFF.toByte(), 0xD9.toByte())

    private val meta = PhotoMeta(
        description = PhotoMeta.describe("fusion+ultra_hdr", "base"),
        make = "OPPO",
        model = "CPH2841",
        dateTime = "2026:10:17 17:31:59",
        orientation = 6,
        exposureTimeS = 1 / 120.0,
        fNumber = 1.8,
        iso = 100,
        focalLengthMm = 6.7
    )

    /** Reads the ASCII/SHORT/RATIONAL values of a big-endian TIFF IFD at [at] (relative to the TIFF start). */
    private fun readIfd(tiff: ByteBuffer, at: Int): Map<Int, Any> {
        val out = HashMap<Int, Any>()
        val n = tiff.getShort(at).toInt()
        for (i in 0 until n) {
            val e = at + 2 + i * 12
            val tag = tiff.getShort(e).toInt() and 0xFFFF
            val type = tiff.getShort(e + 2).toInt()
            val count = tiff.getInt(e + 4)
            out[tag] = when (type) {
                2 -> {
                    val off = if (count <= 4) e + 8 else tiff.getInt(e + 8)
                    String(ByteArray(count - 1) { tiff.get(off + it) }, Charsets.US_ASCII)
                }
                3 -> tiff.getShort(e + 8).toInt()
                4 -> tiff.getInt(e + 8)
                5 -> tiff.getInt(tiff.getInt(e + 8)).toDouble() / tiff.getInt(tiff.getInt(e + 8) + 4)
                else -> error("type $type")
            }
        }
        return out
    }

    @Test
    fun exifSegmentReadsBack() {
        val app1 = JpegMetadata.exifSegment(meta)
        assertEquals(0xE1, app1[1].toInt() and 0xFF)
        assertEquals(app1.size - 2, ((app1[2].toInt() and 0xFF) shl 8) or (app1[3].toInt() and 0xFF))
        assertEquals("Exif", String(app1, 4, 4, Charsets.US_ASCII))
        val tiff = ByteBuffer.wrap(app1, 10, app1.size - 10).slice()
        val ifd0 = readIfd(tiff, tiff.getInt(4))
        assertEquals("UltimateCamera hdr=fusion+ultra_hdr render=base", ifd0[JpegMetadata.TAG_DESCRIPTION])
        assertEquals("OPPO", ifd0[JpegMetadata.TAG_MAKE])
        assertEquals("CPH2841", ifd0[JpegMetadata.TAG_MODEL])
        assertEquals(6, ifd0[JpegMetadata.TAG_ORIENTATION])
        assertEquals("UltimateCamera", ifd0[JpegMetadata.TAG_SOFTWARE])
        assertEquals("2026:10:17 17:31:59", ifd0[JpegMetadata.TAG_DATETIME])
        val exif = readIfd(tiff, ifd0[JpegMetadata.TAG_EXIF_IFD] as Int)
        assertEquals(1 / 120.0, exif[JpegMetadata.TAG_EXPOSURE_TIME] as Double, 1e-9)
        assertEquals(1.8, exif[JpegMetadata.TAG_F_NUMBER] as Double, 1e-6)
        assertEquals(100, exif[JpegMetadata.TAG_ISO])
        assertEquals(6.7, exif[JpegMetadata.TAG_FOCAL_LENGTH] as Double, 1e-6)
        assertEquals("2026:10:17 17:31:59", exif[JpegMetadata.TAG_DATETIME_ORIGINAL])
    }

    @Test
    fun exifGoesRightAfterSoiAndTheRestIsCopiedVerbatim() {
        val out = ByteArrayOutputStream()
        JpegMetadata.writeWithExif(ultraHdr.inputStream(), out, meta)
        val bytes = out.toByteArray()
        val inserted = JpegMetadata.exifSegment(meta) + JpegMetadata.commentSegment(meta.description)
        assertArrayEquals(ultraHdr.copyOfRange(0, 2), bytes.copyOfRange(0, 2))
        assertArrayEquals(inserted, bytes.copyOfRange(2, 2 + inserted.size))
        // MPF header and gain-map image keep their distance: everything after SOI is the original, in order.
        assertArrayEquals(ultraHdr.copyOfRange(2, ultraHdr.size), bytes.copyOfRange(2 + inserted.size, bytes.size))
    }

    @Test
    fun commentGoesAfterTheMakersExifAndBeforeTheMpfSegment() {
        val makerExif = seg(0xE1, "Exif\u0000\u0000maker".toByteArray())
        val vendor = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + makerExif + ultraHdr.copyOfRange(2, ultraHdr.size)
        val out = ByteArrayOutputStream()
        JpegMetadata.writeWithComment(vendor.inputStream(), out, "UltimateCamera hdr=extension_hdr")
        val bytes = out.toByteArray()
        val comment = JpegMetadata.commentSegment("UltimateCamera hdr=extension_hdr")
        val app0 = seg(0xE0, "JFIF\u0000".toByteArray())
        val head = 2 + makerExif.size + app0.size
        assertArrayEquals(vendor.copyOfRange(0, head), bytes.copyOfRange(0, head))
        assertArrayEquals(comment, bytes.copyOfRange(head, head + comment.size))
        assertArrayEquals(vendor.copyOfRange(head, vendor.size), bytes.copyOfRange(head + comment.size, bytes.size))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("hdr=extension_hdr"))
    }

    @Test(expected = java.io.IOException::class)
    fun notAJpegIsRefused() {
        JpegMetadata.writeWithExif(byteArrayOf(1, 2, 3).inputStream(), ByteArrayOutputStream(), meta)
    }
}
