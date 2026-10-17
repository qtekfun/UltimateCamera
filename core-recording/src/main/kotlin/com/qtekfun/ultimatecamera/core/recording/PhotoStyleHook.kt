package com.qtekfun.ultimatecamera.core.recording

import android.graphics.Bitmap

/**
 * The one place the photo style engine plugs in (D74): it runs last on every photo we render ourselves, after the base
 * rendering and before encoding, on the upright ARGB_8888 bitmap (the gain map of an Ultra HDR photo is left alone).
 * A no-op until the style engine sets [stage] (its `applyStyle` with the chosen 33³ LUT).
 */
object PhotoStyleHook {
    @Volatile
    var stage: (Bitmap) -> Unit = {}
}
