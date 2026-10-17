package com.qtekfun.ultimatecamera.core.camera

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthOutputsTest {
    private val jpeg = PixelSize(4000, 3000)
    private val pair = StereoPair("2", "3", LensOptics(6.9f, 9.8f, 7.4f), LensOptics(2.2f, 5.6f, 4.2f))

    @Test
    fun aDepthCameraGetsOneDepth16StreamWithTheJpegAspect() {
        val support = DepthSupport(depth16 = listOf(PixelSize(640, 360), PixelSize(640, 480), PixelSize(320, 240)))
        val outputs = DepthCaptureStream.auxOutputs(null, support, jpeg) { error("no stereo lookup without a pair") }
        assertEquals(listOf(AuxOutput(PixelSize(640, 480), ImageFormat.DEPTH16, null)), outputs)
    }

    @Test
    fun aStereoPairGetsOneYuvStreamPerPhysicalCamera() {
        var asked: StereoPair? = null
        val outputs = DepthCaptureStream.auxOutputs(pair, DepthSupport(), jpeg) {
            asked = it
            PixelSize(640, 480)
        }
        assertEquals(pair, asked)
        assertEquals(
            listOf(AuxOutput(PixelSize(640, 480), ImageFormat.YUV_420_888, "2"), AuxOutput(PixelSize(640, 480), ImageFormat.YUV_420_888, "3")),
            outputs
        )
    }

    @Test
    fun portraitCapabilities() {
        val depth = setOf(android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)
        val logical = setOf(android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        assertTrue(PortraitProbe.depthOutput(depth))
        assertTrue(!PortraitProbe.depthOutput(logical))
        assertTrue(PortraitProbe.logicalMultiCamera(logical + depth))
        assertTrue(!PortraitProbe.logicalMultiCamera(emptySet()))
        // Bracketing by manual exposure needs MANUAL_SENSOR.
        assertTrue(PhotoHdrProbe.manualSensor(setOf(android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)))
        assertTrue(!PhotoHdrProbe.manualSensor(depth))
        assertTrue(!PhotoHdrProbe.manualSensor(null))
    }

    @Test
    fun missingOutputsAreASessionRefusal() {
        val noDepth = assertThrows(SessionRejectedException::class.java) { DepthCaptureStream.auxOutputs(null, DepthSupport(), jpeg) { null } }
        assertEquals("no DEPTH16 output", noDepth.message)
        val noShared = assertThrows(SessionRejectedException::class.java) { DepthCaptureStream.auxOutputs(pair, DepthSupport(), jpeg) { null } }
        assertTrue(noShared.message!!.contains("stereo pair"))
    }
}
