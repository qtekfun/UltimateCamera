package com.qtekfun.ultimatecamera.core.gpu.portrait

import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthPng16Test {
    private val meta = DepthPng16.Meta(rotation = 90, fNumber = 2.8f, focusX = 0.25f, focusY = 0.6f)

    /** Inverse depth from 0.3 D (3.3 m) to 2.5 D (40 cm), a fifth of it without depth. */
    private fun depth(w: Int = 40, h: Int = 30, seed: Long = 11): Plane {
        val random = Random(seed)
        return Plane(w, h).apply {
            for (i in data.indices) data[i] = if (random.nextInt(5) == 0) Bokeh.INVALID else 0.3f + random.nextFloat() * 2.2f
            data[0] = 0.3f
            data[1] = 2.5f
        }
    }

    private fun encode(depth: Plane, m: DepthPng16.Meta = meta): ByteArray = ByteArrayOutputStream().also { DepthPng16.write(depth, m, it) }.toByteArray()

    private class Chunk(val type: String, val body: ByteArray, val crc: Int)

    private fun chunks(png: ByteArray): List<Chunk> {
        val buffer = ByteBuffer.wrap(png, 8, png.size - 8)
        val out = ArrayList<Chunk>()
        while (buffer.hasRemaining()) {
            val length = buffer.int
            val type = ByteArray(4).also { buffer.get(it) }.toString(Charsets.ISO_8859_1)
            val body = ByteArray(length).also { buffer.get(it) }
            out += Chunk(type, body, buffer.int)
        }
        return out
    }

    @Test
    fun theDepthComesBackInDioptresWithItsRealRangeAndHoles() {
        val original = depth()
        val decoded = DepthPng16.read(encode(original).inputStream())
        assertEquals(meta, decoded.meta)
        assertEquals(original.width, decoded.depth.width)
        assertEquals(original.height, decoded.depth.height)
        val quantum = DepthPng16.quantum(2.2f)
        for (i in original.data.indices) {
            val v = original.data[i]
            if (v <= Bokeh.INVALID) {
                assertEquals("hole $i", Bokeh.INVALID, decoded.depth.data[i], 0f)
            } else {
                // Real dioptres, not a 0..1 map: 2.5 D comes back as 2.5 D, so the CoC of an edit is the capture's.
                assertEquals("pixel $i", v, decoded.depth.data[i], quantum + 1e-5f)
            }
        }
        assertEquals(2.5f, decoded.depth.data[1], 1e-5f)
    }

    @Test
    fun itIsAValidSixteenBitGreyPngUprightWithWhiteNear() {
        val original = depth(w = 40, h = 30)
        val png = encode(original)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10), png.copyOf(8))
        val list = chunks(png)
        assertEquals(listOf("IHDR", "tEXt", "IDAT", "IEND"), list.map { it.type })
        for (c in list) {
            val crc = CRC32().apply {
                update(c.type.toByteArray(Charsets.ISO_8859_1))
                update(c.body)
            }
            assertEquals(c.type, crc.value.toInt(), c.crc)
        }
        val ihdr = ByteBuffer.wrap(list[0].body)
        // Turned 90° clockwise: 30 wide, 40 high, like the upright photo.
        assertEquals(30, ihdr.int)
        assertEquals(40, ihdr.int)
        assertEquals(16, ihdr.get().toInt())
        assertEquals(0, ihdr.get().toInt())
        val text = list[1].body.toString(Charsets.ISO_8859_1)
        assertTrue(text, text.startsWith(DepthPng16.KEYWORD + "\u0000v=1;near=2.5"))
        val raw = InflaterInputStream(list[2].body.inputStream()).readBytes()
        assertEquals(40 * (1 + 2 * 30), raw.size)
        // Sensor pixel (1, 0), the nearest, lands at upright (29, 1) with the top code; (0, 0), the farthest, at (29, 0) with 1.
        fun code(x: Int, y: Int) = (raw[y * 61 + 1 + 2 * x].toInt() and 0xFF shl 8) or (raw[y * 61 + 2 + 2 * x].toInt() and 0xFF)
        assertEquals(65535, code(29, 1))
        assertEquals(1, code(29, 0))
    }

    @Test
    fun aMapWithoutAnyDepthStaysEmpty() {
        val empty = Plane(4, 3).apply { data.fill(Bokeh.INVALID) }
        val decoded = DepthPng16.read(encode(empty, meta.copy(rotation = 0)).inputStream())
        assertTrue(decoded.depth.data.all { it == Bokeh.INVALID })
        val flat = Plane(4, 3).apply { data.fill(1.25f) }
        assertTrue(DepthPng16.read(encode(flat, meta.copy(rotation = 0)).inputStream()).depth.data.all { it == 1.25f })
    }

    @Test
    fun everyPngRowFilterIsUndone() {
        val original = depth(w = 9, h = 6, seed = 3)
        val png = encode(original, meta.copy(rotation = 0))
        val list = chunks(png)
        val raw = InflaterInputStream(list[2].body.inputStream()).readBytes()
        val stride = 18
        // Re-filter each row with filter (row % 5), as other encoders would, and decode it.
        val filtered = ByteArrayOutputStream()
        var previous = ByteArray(stride)
        for (y in 0 until 6) {
            val row = raw.copyOfRange(y * (stride + 1) + 1, (y + 1) * (stride + 1))
            val f = y % 5
            filtered.write(f)
            for (i in 0 until stride) {
                val a = if (i >= 2) row[i - 2].toInt() and 0xFF else 0
                val b = previous[i].toInt() and 0xFF
                val c = if (i >= 2) previous[i - 2].toInt() and 0xFF else 0
                val predictor = when (f) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    else -> paeth(a, b, c)
                }
                filtered.write(((row[i].toInt() and 0xFF) - predictor) and 0xFF)
            }
            previous = row
        }
        val idat = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out, Deflater()).use { it.write(filtered.toByteArray()) } }.toByteArray()
        val rebuilt = ByteArrayOutputStream()
        rebuilt.write(png, 0, 8)
        for (c in list) writeChunk(rebuilt, c.type, if (c.type == "IDAT") idat else c.body)
        val decoded = DepthPng16.read(rebuilt.toByteArray().inputStream())
        val reference = DepthPng16.read(png.inputStream())
        assertArrayEquals(reference.depth.data, decoded.depth.data, 0f)
    }

    @Test
    fun otherFilesAreRefused() {
        assertThrows(IOException::class.java) { DepthPng16.read(byteArrayOf(1, 2, 3).inputStream()) }
        // A PNG without our text chunk: some other picture named like a depth map.
        val png = encode(depth(), meta)
        val list = chunks(png)
        val foreign = ByteArrayOutputStream().apply {
            write(png, 0, 8)
            for (c in list) if (c.type != "tEXt") writeChunk(this, c.type, c.body)
        }
        assertThrows(IOException::class.java) { DepthPng16.read(foreign.toByteArray().inputStream()) }
        // Truncated.
        assertThrows(IOException::class.java) { DepthPng16.read(png.copyOf(png.size / 2).inputStream()) }
    }

    @Test
    fun quarterTurnsAndPointMapsAgree() {
        val p = Plane(5, 3).apply { for (i in data.indices) data[i] = i.toFloat() }
        for (turn in listOf(0, 90, 180, 270, -90)) {
            val turned = PlaneTurn.rotate(p, turn)
            assertArrayEquals(p.data, PlaneTurn.rotate(turned, -turn).data, 0f)
            // The pixel at sensor (3, 1) is where toUpright says on the turned plane.
            val (u, v) = PlaneTurn.toUpright((3 + 0.5f) / 5, (1 + 0.5f) / 3, turn)
            val ux = (u * turned.width).toInt()
            val uy = (v * turned.height).toInt()
            assertEquals("turn $turn", p[3, 1], turned[ux, uy], 0f)
            val (x, y) = PlaneTurn.toSensor(u, v, turn)
            assertEquals((3 + 0.5f) / 5, x, 1e-6f)
            assertEquals((1 + 0.5f) / 3, y, 1e-6f)
        }
        assertThrows(IllegalArgumentException::class.java) { PlaneTurn.rotate(p, 45) }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) {
            a
        } else if (pb <= pc) {
            b
        } else {
            c
        }
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, body: ByteArray) {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(ByteBuffer.allocate(4).putInt(body.size).array())
        out.write(t)
        out.write(body)
        val crc = CRC32().apply {
            update(t)
            update(body)
        }
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }
}
