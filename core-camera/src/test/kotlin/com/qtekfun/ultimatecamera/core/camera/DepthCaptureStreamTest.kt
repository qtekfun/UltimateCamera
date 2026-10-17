package com.qtekfun.ultimatecamera.core.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepthCaptureStreamTest {
    @Test
    fun depth16IsReadInNativeByteOrderWhateverTheBufferSays() {
        val native = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder())
        native.asShortBuffer().put(shortArrayOf(1000, (0x2000 or 250).toShort(), 7))
        // A fresh buffer from the camera may report big-endian; the samples are still native.
        val samples = DepthCaptureStream.depth16Samples(native.order(ByteOrder.BIG_ENDIAN))
        assertArrayEquals(shortArrayOf(1000, (0x2000 or 250).toShort(), 7), samples)
    }

    @Test
    fun lumaIsPackedFromAPaddedPlane() {
        // 3 x 2 image in rows of 5 bytes.
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 0, 0, 4, 5, 6, 0, 0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), DepthCaptureStream.packLuma(buffer, rowStride = 5, pixelStride = 1, width = 3, height = 2))
    }

    @Test
    fun lumaWithAPixelStrideSkipsTheInterleavedBytes() {
        val buffer = ByteBuffer.wrap(byteArrayOf(1, 9, 2, 9, 3, 9, 4, 9))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), DepthCaptureStream.packLuma(buffer, rowStride = 4, pixelStride = 2, width = 2, height = 2))
    }

    @Test
    fun depthSizeFollowsThePhotoAspect() {
        val sizes = listOf(PixelSize(640, 480), PixelSize(320, 240), PixelSize(640, 360))
        assertEquals(PixelSize(640, 480), DepthCaptureStream.closestAspect(sizes, PixelSize(4000, 3000)))
        assertEquals(PixelSize(640, 360), DepthCaptureStream.closestAspect(sizes, PixelSize(3840, 2160)))
        assertNull(DepthCaptureStream.closestAspect(emptyList(), PixelSize(4000, 3000)))
    }

    @Test
    fun stereoSizeIsSharedSmallAndWithThePhotoAspect() {
        val main = setOf(PixelSize(1920, 1080), PixelSize(640, 480), PixelSize(640, 360), PixelSize(320, 240))
        val second = setOf(PixelSize(1920, 1080), PixelSize(640, 360), PixelSize(320, 240))
        assertEquals(PixelSize(320, 240), DepthCaptureStream.sharedStereoSize(main, second, PixelSize(4000, 3000)))
        assertEquals(PixelSize(640, 360), DepthCaptureStream.sharedStereoSize(main, second, PixelSize(3840, 2160)))
        assertNull(DepthCaptureStream.sharedStereoSize(setOf(PixelSize(1920, 1080)), second, PixelSize(4000, 3000)))
    }

    @Test
    fun depthSupportListsSourcesInPreferenceOrder() {
        val pair = StereoPair("2", "3", LensOptics(6.9f, 9.8f, 7.4f), LensOptics(2f, 5.6f, 4.2f))
        val all = DepthSupport(depth16 = listOf(PixelSize(640, 480)), bokehExtension = true, stereo = pair)
        assertEquals(listOf(DepthSource.DEPTH_CAMERA, DepthSource.VENDOR_BOKEH, DepthSource.STEREO), all.sources)
        assertEquals(1f, pair.copy(secondOptics = pair.mainOptics).secondCrop, 1e-6f)
    }
}
