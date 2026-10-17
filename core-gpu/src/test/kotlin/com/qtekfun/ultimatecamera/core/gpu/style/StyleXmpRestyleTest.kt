package com.qtekfun.ultimatecamera.core.gpu.style

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The restyle half of the style XMP (D79 follow-up): reading what a photo says, and rewriting it on new pixels. */
class StyleXmpRestyleTest {
    private val exif = "Exif\u0000\u0000MM\u0000*orientation=6".toByteArray(Charsets.ISO_8859_1)
    private val comment = "UltimateCamera hdr=fusion render=base".toByteArray(Charsets.ISO_8859_1)
    private val jfif = "JFIF\u0000\u0001\u0001".toByteArray(Charsets.ISO_8859_1)
    private val gainMapXmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta hdrgm:Version=\"1.0\"/>".toByteArray(Charsets.ISO_8859_1)

    /** Synthetic JPEG: SOI, the segments, a quantisation table, then SOS, entropy bytes [scan] and EOI. */
    private fun jpeg(vararg segments: Pair<Int, ByteArray>, scan: ByteArray = byteArrayOf(0x12, 0x34)): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        for ((marker, data) in segments.toList() + (0xDB to ByteArray(5) { it.toByte() })) {
            write(0xFF)
            write(marker)
            write(data.size + 2 shr 8)
            write(data.size + 2 and 0xFF)
            write(data)
        }
        write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2))
        write(scan)
        write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
    }.toByteArray()

    private fun styled(style: StyleParams, base: String?): ByteArray {
        val out = ByteArrayOutputStream()
        StyleXmp.insert(ByteArrayInputStream(jpeg(0xE1 to exif, 0xFE to comment)), out, style, base)
        return out.toByteArray()
    }

    private fun restyle(original: ByteArray, encoded: ByteArray, style: StyleParams, base: String?): Pair<Boolean, ByteArray> {
        val out = ByteArrayOutputStream()
        val inserted = StyleXmp.restyle(ByteArrayInputStream(original), ByteArrayInputStream(encoded), out, style, base)
        return inserted to out.toByteArray()
    }

    private fun ByteArray.text() = String(this, Charsets.ISO_8859_1)

    private fun ByteArray.count(needle: String): Int = text().windowed(needle.length).count { it == needle }

    @Test
    fun inspectReadsTheStyleAndTheBaseName() {
        val vibrant = StyleParams(StylePreset.VIBRANT, tone = 0.3f, warmth = -0.2f, saturation = 0.1f, intensity = 0.7f)
        val info = StyleXmp.inspect(ByteArrayInputStream(styled(vibrant, "UC_P001_base.jpg")))
        assertEquals(vibrant, info.style)
        assertEquals("UC_P001_base.jpg", info.base)
        assertFalse(info.foreignXmp)
        // Without a kept copy there is no base name.
        assertNull(StyleXmp.inspect(ByteArrayInputStream(styled(vibrant, null))).base)
    }

    @Test
    fun inspectTellsAForeignXmpFromNoXmpAtAll() {
        val ultraHdr = StyleXmp.inspect(ByteArrayInputStream(jpeg(0xE1 to exif, 0xE1 to gainMapXmp)))
        assertNull(ultraHdr.style)
        assertTrue(ultraHdr.foreignXmp)
        assertEquals(StyleXmp.Info.NONE, StyleXmp.inspect(ByteArrayInputStream(jpeg(0xE1 to exif))))
        assertEquals(StyleXmp.Info.NONE, StyleXmp.inspect(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun restyleKeepsTheOriginalExifAndCommentAndRewritesTheStyle() {
        val original = styled(StyleParams.of(StylePreset.WARM), "UC_P001_base.jpg")
        // The new pixels as Bitmap.compress makes them: JFIF, no EXIF, other scan bytes.
        val pixels = byteArrayOf(0x55, 0x66, 0x77, 0xFF.toByte(), 0x00, 0x11)
        val encoded = jpeg(0xE0 to jfif, scan = pixels)
        val mono = StyleParams(StylePreset.MONO, tone = -0.4f, intensity = 0.5f)
        val (inserted, bytes) = restyle(original, encoded, mono, "UC_P001_base.jpg")
        assertTrue(inserted)
        val info = StyleXmp.inspect(ByteArrayInputStream(bytes))
        assertEquals(mono, info.style)
        assertEquals("UC_P001_base.jpg", info.base)
        // Exactly one style packet (the old Warm one is gone), the shot's EXIF and comment kept, JFIF first.
        assertEquals(1, bytes.count("uc:Style"))
        assertFalse(bytes.text().contains("style:warm"))
        assertEquals(1, bytes.count("orientation=6"))
        assertEquals(1, bytes.count("render=base"))
        val text = bytes.text()
        assertTrue(text.indexOf("JFIF") < text.indexOf("Exif") && text.indexOf("Exif") < text.indexOf("uc:Style"))
        // The image data is the encoded one, byte for byte.
        val tail = encoded.copyOfRange(encoded.size - (pixels.size + 8), encoded.size)
        assertArrayEquals(tail, bytes.copyOfRange(bytes.size - tail.size, bytes.size))
    }

    @Test
    fun restyleDropsExifAndStyleTheEncoderMayHaveWritten() {
        val original = styled(StyleParams.of(StylePreset.COOL), null)
        val staleExif = "Exif\u0000\u0000stale".toByteArray(Charsets.ISO_8859_1)
        val withStale = ByteArrayOutputStream().also {
            StyleXmp.insert(ByteArrayInputStream(jpeg(0xE1 to staleExif)), it, StyleParams.of(StylePreset.FADED))
        }.toByteArray()
        val (inserted, bytes) = restyle(original, withStale, StyleParams.of(StylePreset.NATURAL), null)
        assertTrue(inserted)
        assertFalse(bytes.text().contains("stale"))
        assertFalse(bytes.text().contains("style:faded"))
        assertEquals(StyleParams.of(StylePreset.NATURAL), StyleXmp.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun aGainMapXmpOnTheNewPixelsStaysAndGetsNoStyle() {
        val original = styled(StyleParams.of(StylePreset.VIBRANT), "b.jpg")
        val encoded = jpeg(0xE1 to gainMapXmp, 0xE2 to "MPF\u0000offsets".toByteArray(Charsets.ISO_8859_1))
        val (inserted, bytes) = restyle(original, encoded, StyleParams.of(StylePreset.MONO), "b.jpg")
        assertFalse(inserted)
        assertEquals(0, bytes.count("uc:Style"))
        assertEquals(1, bytes.count("hdrgm:Version"))
        assertEquals(1, bytes.count("MPF"))
        assertEquals(1, bytes.count("orientation=6"))
    }

    @Test
    fun restyleRefusesWhatIsNotAJpeg() {
        val good = jpeg()
        val notJpeg = byteArrayOf(0, 1, 2)
        listOf(notJpeg to good, good to notJpeg).forEach { (original, encoded) ->
            val failed = runCatching { restyle(original, encoded, StyleParams.of(StylePreset.MONO), null) }
            assertTrue(failed.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun restyledNamesNumberOnAndNeverCollide() {
        assertEquals("UC_1_CAM0_P001_s1.jpg", StyleXmp.restyledName("UC_1_CAM0_P001.jpg", emptySet()))
        assertEquals("UC_1_CAM0_P001_s3.jpg", StyleXmp.restyledName("UC_1_CAM0_P001.jpg", setOf("UC_1_CAM0_P001_s1.jpg", "UC_1_CAM0_P001_s2.jpg")))
        // A copy of a copy numbers on from the photo, not `_s1_s1`.
        assertEquals("UC_1_CAM0_P001_s2.jpg", StyleXmp.restyledName("UC_1_CAM0_P001_s1.jpg", setOf("UC_1_CAM0_P001_s1.jpg")))
        assertEquals("photo_s1", StyleXmp.restyledName("photo", emptySet()))
    }
}
