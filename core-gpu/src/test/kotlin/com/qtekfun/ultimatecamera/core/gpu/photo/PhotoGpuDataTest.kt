package com.qtekfun.ultimatecamera.core.gpu.photo

import com.qtekfun.ultimatecamera.core.gpu.CubeLut
import com.qtekfun.ultimatecamera.core.gpu.hdr.FusionTone
import com.qtekfun.ultimatecamera.core.gpu.hdr.GpuPhotoLookShader
import com.qtekfun.ultimatecamera.core.gpu.hdr.GuidedBase
import com.qtekfun.ultimatecamera.core.gpu.hdr.LogLuma
import com.qtekfun.ultimatecamera.core.gpu.hdr.LookPlan
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.hdr.RenderParams
import com.qtekfun.ultimatecamera.core.gpu.hdr.ToneMap
import com.qtekfun.ultimatecamera.core.gpu.style.GpuStyle
import com.qtekfun.ultimatecamera.core.gpu.style.StyleEngine
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CPU side of the GPU photo passes (D90): table layout, limits, uniforms and the tables the shaders read. */
class PhotoGpuDataTest {
    @Test
    fun tablesAreLaidOutInRowsOf64() {
        assertEquals(65, PhotoGpuData.tableRows(4097))
        assertEquals(1, PhotoGpuData.tableRows(64))
        val padded = PhotoGpuData.padded(floatArrayOf(1f, 2f), 4)
        assertArrayEquals(floatArrayOf(1f, 2f, 0f, 0f), padded, 0f)
        val same = floatArrayOf(3f)
        assertTrue(PhotoGpuData.padded(same, 1) === same)
    }

    @Test
    fun imagesTooWideForTheGpuAreRefused() {
        assertNull(PhotoGpuData.problem(4080, 3072, 16384))
        assertNull(PhotoGpuData.problem(4080, 30000, 8192)) // Strips: only the width must fit.
        assertTrue(PhotoGpuData.problem(9000, 100, 8192)!!.contains("8192"))
        assertTrue(PhotoGpuData.problem(0, 100, 8192)!!.contains("empty"))
    }

    @Test
    fun floatBuffersAreNativeOrderAndRewound() {
        val buffer = PhotoGpuData.floats(floatArrayOf(1.5f, -2f))
        assertEquals(0, buffer.position())
        assertEquals(ByteOrder.nativeOrder(), buffer.order())
        assertEquals(-2f, buffer.asFloatBuffer().get(1), 0f)
    }

    @Test
    fun lookUniformsCarryThePlanExactly() {
        val params = RenderParams.BASE
        val tone = ToneMap(params, 0.02f, 0.8f)
        val plan = LookPlan(16, 8, 2, params, GuidedBase(Plane(8, 4), Plane(8, 4)), tone, 1.4f)
        val u = GpuPhotoLookShader.uniforms(plan)
        assertEquals(GpuPhotoLookShader.F.entries.size, u.size)
        assertEquals(tone.whiteGain, u[GpuPhotoLookShader.F.WHITE_GAIN.ordinal], 0f)
        assertEquals(tone.floor + ToneMap.SHADOW_FADE_STOPS, u[GpuPhotoLookShader.F.FLOOR_TOP.ordinal], 0f)
        assertEquals(1.4f, u[GpuPhotoLookShader.F.GAIN.ordinal], 0f)
        assertEquals(ToneMap.OUT_STEP, u[GpuPhotoLookShader.F.OUT_STEP.ordinal], 0f)
        assertEquals(5f / 60f, u[GpuPhotoLookShader.F.SKIN_HUE_LO.ordinal], 0f)
        assertEquals(8, plan.lowW)
        assertEquals(4, plan.lowH)
        // The tables the shader reads are the ones the CPU interpolates.
        assertEquals(ToneMap.OUT_SIZE + 1, tone.outTable().size)
        assertEquals(ToneMap.FINISH_SIZE + 1, tone.finishTable().size)
        assertEquals(LogLuma.SIZE + 1, LogLuma.table().size)
        assertEquals(LogLuma.of(0.5f), LogLuma.table()[LogLuma.SIZE / 2], 0f)
        assertEquals(tone.finish(1f), tone.finishTable().last(), 0f)
        assertTrue(GpuPhotoLookShader.FRAGMENT.contains("uniform float uF[${u.size}]"))
    }

    @Test
    fun shadersAreBuiltOnTheSharedHeader() {
        assertTrue(GpuStyle.FRAGMENT.startsWith(GpuStripPass.HEADER))
        assertTrue(GpuStripPass.HEADER.contains("void emit("))
    }

    @Test
    fun theParallelStyleLatticeIsTheGeneratedOne() {
        val params = StyleParams(StylePreset.SOFT_FILM, tone = 0.3f, warmth = 0.2f, saturation = -0.4f, intensity = 0.8f)
        val expected = CubeLut.generate(StyleEngine.LUT_SIZE) { r, g, b ->
            val out = FloatArray(3)
            StyleEngine.transform(params, r, g, b, out)
            Triple(out[0], out[1], out[2])
        }
        assertArrayEquals(expected.data, StyleEngine.lutFor(params).data, 0f)
    }

    @Test
    fun fusionToneTableIsACopy() {
        val tone = FusionTone.of(0.3f, 0.8f, 0.5f)
        val table = tone.table()
        assertEquals(256, table.size)
        for (v in 0..255) assertEquals(tone[v], table[v])
        table[0] = 99
        assertEquals(tone.table()[0], tone[0])
    }
}
