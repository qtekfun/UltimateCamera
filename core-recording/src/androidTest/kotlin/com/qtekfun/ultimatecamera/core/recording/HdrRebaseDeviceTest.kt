package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.core.gpu.hdr.GainmapRebase
import com.qtekfun.ultimatecamera.core.gpu.hdr.Srgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D84 on the platform's `Gainmap`: a dim Ultra HDR SDR base (white at 206, as the OPPO's JPEG_R) is brought to about
 * 240 by the base rendering, and its gain map is rebased so the HDR highlight keeps its brightness.
 */
@RunWith(AndroidJUnit4::class)
class HdrRebaseDeviceTest {
    @Test
    fun theSdrBaseGetsItsWhiteAndTheHdrHighlightStays() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val w = 256
        val h = 192
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Left: the white wall at 206; right: a dark area with a black corner (so the scene has a black to find).
        val px = IntArray(w * h) { i ->
            val x = i % w
            val v = if (x < w / 2) {
                206
            } else if (x < w - 16) {
                60
            } else {
                20
            }
            -0x1000000 or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
        val contents = Bitmap.createBitmap(w / 4, h / 4, Bitmap.Config.ALPHA_8)
        contents.setPixels(IntArray(contents.width * contents.height) { 200 shl 24 }, 0, contents.width, 0, 0, contents.width, contents.height)
        val ratioMax = 4.925f
        bitmap.gainmap = Gainmap(contents).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(ratioMax, ratioMax, ratioMax)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(0f, 0f, 0f)
            setEpsilonHdr(0f, 0f, 0f)
            displayRatioForFullHdr = ratioMax
        }
        PhotoFinisher.render(bitmap)
        val wall = bitmap.getPixel(w / 4, h / 2) and 0xFF
        assertEquals("wall", 240.0, wall.toDouble(), 8.0)
        val map = requireNotNull(bitmap.gainmap).gainmapContents
        val gain = map.getPixel(map.width / 8, map.height / 2) ushr 24
        assertTrue("gain $gain", gain < 200)
        val math = GainmapRebase(floatArrayOf(1f, 1f, 1f), FloatArray(3) { ratioMax }, floatArrayOf(1f, 1f, 1f), FloatArray(3))
        val before = math.hdr(Srgb.decode(206 / 255f), 200)
        val after = math.hdr(Srgb.decode(wall / 255f), gain)
        assertEquals("HDR highlight", before, after, before * 0.05)
        bitmap.recycle()
    }
}
