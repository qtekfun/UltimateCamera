package com.qtekfun.ultimatecamera.core.recording

import android.media.ExifInterface
import com.qtekfun.ultimatecamera.core.camera.DepthFrame
import com.qtekfun.ultimatecamera.core.camera.LensOptics
import com.qtekfun.ultimatecamera.core.camera.StereoPair
import com.qtekfun.ultimatecamera.core.gpu.hdr.Plane
import com.qtekfun.ultimatecamera.core.gpu.portrait.Bokeh
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitMapsTest {
    @Test
    fun edgeMaskMarksOnlyWhereTheBlurChanges() {
        val coc = Plane(8, 4, FloatArray(32) { i -> if (i % 8 < 4) 0f else 10f })
        val mask = PortraitMaps.edgeMask(coc)
        for (y in 0 until 4) {
            assertFalse(mask[y * 8 + 1])
            assertTrue(mask[y * 8 + 3])
            assertTrue(mask[y * 8 + 4])
            assertFalse(mask[y * 8 + 6])
        }
        assertEquals(10f, PortraitMaps.spread(coc, 3, 0), 0f)
        assertEquals(0f, PortraitMaps.spread(coc, 7, 3), 0f)
    }

    @Test
    fun focusFallsBackToAWiderWindowWhenThePointHasNoDepth() {
        val depth = Plane(40, 30).apply { data.fill(Bokeh.INVALID) }
        depth[24, 15] = 1.25f // Inside the wide window (40 / 8 = 5), outside the default 3.
        assertEquals(1.25f, PortraitMaps.focus(depth, PortraitLook(2f)), 0f)
        depth[20, 15] = 0.5f
        assertEquals(0.5f, PortraitMaps.focus(depth, PortraitLook(2f)), 0f)
    }

    @Test
    fun depth16FramesBecomeInverseDepth() {
        val frame = DepthFrame.Depth16(shortArrayOf(500, 2000), width = 2, height = 1, rowStride = 2)
        val inverse = PortraitMaps.inverseDepth(frame)
        assertEquals(2f, inverse[0, 0], 1e-6f)
        assertEquals(0.5f, inverse[1, 0], 1e-6f)
    }

    @Test
    fun stereoFramesFindTheNearHalfNearer() {
        // Same optics (crop 1): the second camera sees the main's view shifted by 6 px on the left half, 1 px on the right.
        val w = 160
        val h = 80
        val random = Random(3)
        val texture = ByteArray((w + 20) * h) { (random.nextInt(256)).toByte() }
        val main = ByteArray(w * h)
        val second = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                main[y * w + x] = texture[y * (w + 20) + x + 10]
                val shift = if (x < w / 2) 6 else 1
                second[y * w + x] = texture[y * (w + 20) + x + 10 - shift]
            }
        }
        val optics = LensOptics(6.9f, 9.8f, 7.4f)
        val inverse = PortraitMaps.inverseDepth(DepthFrame.Stereo(main, second, w, h, StereoPair("2", "3", optics, optics)))
        assertEquals(w / 2, inverse.width)
        val near = inverse[w / 8, h / 4]
        val far = inverse[w * 3 / 8, h / 4]
        assertTrue("near $near far $far", near > far)
    }

    @Test
    fun exifOrientationFollowsTheTurn() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL, PortraitMaps.exifOrientation(0))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, PortraitMaps.exifOrientation(90))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, PortraitMaps.exifOrientation(180))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, PortraitMaps.exifOrientation(-90))
    }

    @Test
    fun depthPngPixelsAreOpaqueGrey() {
        val pixels = PortraitMaps.greyArgb(Plane(2, 1, floatArrayOf(2f, 0.5f)))
        assertEquals(0xFFFFFFFF.toInt(), pixels[0])
        assertEquals(0xFF000000.toInt(), pixels[1])
    }

    @Test
    fun depthMapNameSitsNextToThePhoto() {
        assertEquals("UC_20261017_120000_CAM0_P001_depth.png", PhotoController.depthName("UC_20261017_120000_CAM0_P001.jpg"))
    }
}
