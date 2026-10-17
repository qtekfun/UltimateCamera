package com.qtekfun.ultimatecamera.core.gpu.gl

import com.qtekfun.ultimatecamera.core.gpu.InputKind
import com.qtekfun.ultimatecamera.core.gpu.meters.MeterShaders
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlCapabilitiesTest {
    @Test
    fun halfFloatTargetsAreCoreFrom32OrComeFromAnExtension() {
        assertTrue(GlCapabilities(3, 2, emptySet()).halfFloatColorBuffer)
        assertFalse(GlCapabilities(3, 0, emptySet()).halfFloatColorBuffer)
        assertTrue(GlCapabilities(3, 0, setOf("GL_EXT_color_buffer_half_float")).halfFloatColorBuffer)
        assertTrue(GlCapabilities(3, 1, setOf("GL_EXT_color_buffer_float")).halfFloatColorBuffer)
    }

    @Test
    fun timerQueriesAndComputeShadersAreReadFromTheContext() {
        assertTrue(GlCapabilities(3, 0, setOf("GL_EXT_disjoint_timer_query")).timerQuery)
        assertFalse(GlCapabilities(3, 2, emptySet()).timerQuery)
        assertTrue(GlCapabilities(3, 1, emptySet()).computeShaders)
        assertFalse(GlCapabilities(3, 0, emptySet()).computeShaders)
        assertTrue(GlCapabilities(4, 0, emptySet()).computeShaders)
    }

    @Test
    fun overlayShaderSamplesTheRightInputAndStartsWithItsVersion() {
        val camera = MeterShaders.overlayFragment(InputKind.EXTERNAL_OES)
        val still = MeterShaders.overlayFragment(InputKind.TEXTURE_2D)
        // #version must be the very first line or the GLSL compiler rejects the shader.
        assertTrue(camera.startsWith("#version 300 es\n#extension GL_OES_EGL_image_external_essl3"))
        assertTrue(camera.contains("samplerExternalOES uInput"))
        assertTrue(still.startsWith("#version 300 es\nuniform highp sampler2D uInput;"))
        assertFalse(still.contains("samplerExternalOES"))
        assertTrue(still.contains("const vec3 kLuma"))
        for (s in listOf(
            MeterShaders.HISTOGRAM_VERTEX,
            MeterShaders.WAVEFORM_VERTEX,
            MeterShaders.VECTORSCOPE_VERTEX,
            MeterShaders.HISTOGRAM_DISPLAY,
            MeterShaders.GUIDE_VERTEX
        )) {
            assertTrue(s.trimStart().startsWith("#version 300 es"))
        }
    }
}
