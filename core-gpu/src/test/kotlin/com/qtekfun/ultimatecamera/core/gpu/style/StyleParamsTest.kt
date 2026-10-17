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

class StyleParamsTest {
    @Test
    fun tokenRoundTripsForEveryPreset() {
        for (preset in StylePreset.entries) {
            val p = StyleParams(preset, tone = -0.33f, warmth = 0.5f, saturation = 1f, intensity = 0.25f)
            assertEquals(p, StyleParams.parse(p.token))
            assertEquals(preset, StyleParams.parse(StyleParams.of(preset).token)?.preset)
        }
        assertEquals("style:vibrant:0.20,-0.10,0.00,1.00", StyleParams(StylePreset.VIBRANT, 0.2f, -0.1f).token)
    }

    @Test
    fun valuesAreClampedAndQuantised() {
        val p = StyleParams(StylePreset.WARM, tone = 3f, warmth = -7f, saturation = 0.123456f, intensity = -1f)
        assertEquals(1f, p.tone)
        assertEquals(-1f, p.warmth)
        assertEquals(0.12f, p.saturation)
        assertEquals(0f, p.intensity)
        assertEquals(0f, StyleParams(StylePreset.WARM, tone = Float.NaN).tone)
        assertEquals(StyleParams(StylePreset.COOL, tone = 0.501f), StyleParams(StylePreset.COOL, tone = 0.4999f))
        assertEquals(StyleParams(StylePreset.COOL).hashCode(), StyleParams.of(StylePreset.COOL).hashCode())
        assertEquals(StyleParams(StylePreset.MONO, 0.1f), StyleParams(StylePreset.MONO).copy(tone = 0.1f))
        assertEquals(StyleParams(StylePreset.MONO).token, StyleParams(StylePreset.MONO).toString())
    }

    @Test
    fun badTokensReadAsNullAndMissingNumbersAsDefaults() {
        assertNull(StyleParams.parse(null))
        assertNull(StyleParams.parse("lib:abc"))
        assertNull(StyleParams.parse("style:unknown:0,0,0,1"))
        assertEquals(StyleParams.of(StylePreset.FADED), StyleParams.parse("style:faded"))
        assertEquals(StyleParams(StylePreset.FADED, tone = 0.5f), StyleParams.parse("style:faded:0.5,x,NaN"))
    }

    @Test
    fun presetIdsAreStableAndNeutral() {
        assertEquals(
            listOf("standard", "rich", "vibrant", "warm", "cool", "natural", "soft_film", "faded", "mono", "mono_contrast"),
            StylePreset.entries.map { it.id }
        )
        assertNull(StylePreset.byId("nope"))
    }

    private fun jpeg(vararg segments: Pair<Int, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        for ((marker, data) in segments) {
            write(0xFF)
            write(marker)
            write(data.size + 2 shr 8)
            write(data.size + 2 and 0xFF)
            write(data)
        }
        // SOS and some entropy-coded bytes, then EOI.
        write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2, 0x12, 0x34, 0xFF.toByte(), 0, 0x56, 0xFF.toByte(), 0xD9.toByte()))
    }.toByteArray()

    @Test
    fun xmpIsInsertedAfterExifAndReadBack() {
        val exif = "Exif\u0000\u0000tiff".toByteArray(Charsets.ISO_8859_1)
        val source = jpeg(0xE0 to "JFIF\u0000".toByteArray(), 0xE1 to exif, 0xDB to ByteArray(65))
        val style = StyleParams(StylePreset.SOFT_FILM, tone = 0.25f, intensity = 0.8f)
        val out = ByteArrayOutputStream()
        assertTrue(StyleXmp.insert(ByteArrayInputStream(source), out, style, base = "UC_P001_base.jpg"))
        val bytes = out.toByteArray()
        assertEquals(style, StyleXmp.read(ByteArrayInputStream(bytes)))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.indexOf("Exif") < text.indexOf("uc:Style"))
        assertTrue(text.contains("uc:Base=\"UC_P001_base.jpg\""))
        // The image data after the headers is untouched.
        assertArrayEquals(source.copyOfRange(source.size - 13, source.size), bytes.copyOfRange(bytes.size - 13, bytes.size))
        assertEquals(source.size + 4 + "http://ns.adobe.com/xap/1.0/\u0000".length + StyleXmp.packet(style, "UC_P001_base.jpg").toByteArray().size, bytes.size)
    }

    @Test
    fun anExistingXmpIsKeptAlone() {
        val other = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta hdrgm/>".toByteArray(Charsets.ISO_8859_1)
        val source = jpeg(0xE1 to other, 0xE2 to ByteArray(10))
        val out = ByteArrayOutputStream()
        assertFalse(StyleXmp.insert(ByteArrayInputStream(source), out, StyleParams.of(StylePreset.MONO)))
        assertArrayEquals(source, out.toByteArray())
        assertNull(StyleXmp.read(ByteArrayInputStream(source)))
    }

    @Test
    fun notAJpegIsRefused() {
        assertNull(StyleXmp.read(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        assertNull(StyleXmp.read(ByteArrayInputStream(jpeg())))
        assertNull(StyleXmp.read(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x10))))
        val failed = runCatching { StyleXmp.insert(ByteArrayInputStream(byteArrayOf(0, 0)), ByteArrayOutputStream(), StyleParams.of(StylePreset.MONO)) }
        assertTrue(failed.exceptionOrNull() is IOException)
    }

    @Test
    fun sidecarNamesAndLabels() {
        assertEquals("UC_1_CAM0_P001_base.jpg", StyleXmp.baseName("UC_1_CAM0_P001.jpg"))
        assertEquals("photo_base", StyleXmp.baseName("photo"))
        assertEquals(StylePreset.entries.size, StylePreset.entries.map { it.label }.toSet().size)
    }
}
