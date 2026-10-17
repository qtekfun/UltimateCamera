package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStyleHookTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    private val work = File("build/tmp/style-hook-test").apply { mkdirs() }

    private fun seg(marker: Int, body: ByteArray) =
        byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg(0xE0, "JFIF\u0000".toByteArray()) + seg(0xDB, ByteArray(65)) +
        byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private val meta = PhotoMeta(description = PhotoMeta.describe("fusion", "base"), make = "maker", model = "model")

    @After
    fun tearDown() {
        PhotoStyleHook.arm(null)
        work.deleteRecursively()
    }

    private fun written(): ByteArray = ByteArrayOutputStream().also { PhotoStyleHook.writeWithExif(ByteArrayInputStream(jpeg), it, meta, work) }.toByteArray()

    @Test
    fun unarmedOrIdentityWritesExactlyTheBaseFile() {
        val plain = ByteArrayOutputStream().also { JpegMetadata.writeWithExif(ByteArrayInputStream(jpeg), it, meta) }.toByteArray()
        PhotoStyleHook.arm(null)
        assertArrayEquals(plain, written())
        PhotoStyleHook.arm(StyleParams.of(StylePreset.STANDARD), File(work, "base.jpg") to "x_base.jpg")
        assertNull(PhotoStyleHook.style)
        assertNull(PhotoStyleHook.baseFile)
        assertArrayEquals(plain, written())
    }

    @Test
    fun anArmedStyleAddsItsXmpAfterTheExifAndNamesTheBaseCopy() {
        val style = StyleParams(StylePreset.WARM, tone = 0.3f)
        PhotoStyleHook.arm(style, File(work, "base.jpg") to "UC_P001_base.jpg")
        assertEquals(style, PhotoStyleHook.style)
        assertEquals("UC_P001_base.jpg", PhotoStyleHook.baseName)
        val bytes = written()
        assertEquals(style, StyleXmp.read(ByteArrayInputStream(bytes)))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.indexOf("Exif") in 0 until text.indexOf("uc:Style"))
        assertTrue(text.contains("uc:Base=\"UC_P001_base.jpg\""))
        assertTrue("staged file left behind", work.listFiles().isNullOrEmpty())
    }
}
