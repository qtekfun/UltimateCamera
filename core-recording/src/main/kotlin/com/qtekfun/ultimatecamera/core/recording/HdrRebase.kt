package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import com.qtekfun.ultimatecamera.core.gpu.hdr.GainmapRebase

/**
 * Android glue of [GainmapRebase] (D84): [before] keeps the SDR picture scaled down to the gain map's size, [after]
 * rewrites the gain map against the rendered picture, so an Ultra HDR photo's HDR rendition stays as the camera or our
 * merge made it while its SDR picture gets the base rendering. Only SDR-base maps (the usual direction) are rebased.
 */
class HdrRebase private constructor(private val before: IntArray) {
    /** Rebases [bitmap]'s gain map on its current pixels. */
    fun after(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val gainmap = bitmap.gainmap ?: return
        val contents = gainmap.gainmapContents
        val w = contents.width
        val h = contents.height
        if (w * h != before.size) return
        val px = IntArray(w * h)
        contents.getPixels(px, 0, w, 0, 0, w, h)
        val math = GainmapRebase(gainmap.ratioMin, gainmap.ratioMax, gainmap.gamma, gainmap.epsilonSdr)
        val rebased = math.rebasePacked(px, contents.config == Bitmap.Config.ALPHA_8, before, scaled(bitmap, w, h))
        val out = contents.copy(contents.config ?: Bitmap.Config.ARGB_8888, true)
        out.setPixels(rebased, 0, w, 0, 0, w, h)
        gainmap.gainmapContents = out
        bitmap.gainmap = gainmap
    }

    companion object {
        /** The rebase for [bitmap], or null when it has no SDR-base gain map (or the platform has no gain maps). */
        fun before(bitmap: Bitmap): HdrRebase? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !bitmap.hasGainmap()) return null
            val gainmap = bitmap.gainmap ?: return null
            val hdrBase = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && gainmap.gainmapDirection != Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR
            val contents = gainmap.gainmapContents
            return if (hdrBase) null else HdrRebase(scaled(bitmap, contents.width, contents.height))
        }

        private fun scaled(bitmap: Bitmap, w: Int, h: Int): IntArray {
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== bitmap) small.recycle()
            return px
        }
    }
}
