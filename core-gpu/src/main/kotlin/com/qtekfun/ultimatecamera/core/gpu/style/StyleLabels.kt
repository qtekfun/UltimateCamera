package com.qtekfun.ultimatecamera.core.gpu.style

import com.qtekfun.ultimatecamera.core.gpu.R

/** The string resource of a preset's name (en + es), shared by the camera screen and Settings. */
val StylePreset.label: Int
    get() = when (this) {
        StylePreset.STANDARD -> R.string.style_standard
        StylePreset.RICH_CONTRAST -> R.string.style_rich
        StylePreset.VIBRANT -> R.string.style_vibrant
        StylePreset.WARM -> R.string.style_warm
        StylePreset.COOL -> R.string.style_cool
        StylePreset.NATURAL -> R.string.style_natural
        StylePreset.SOFT_FILM -> R.string.style_soft_film
        StylePreset.FADED -> R.string.style_faded
        StylePreset.MONO -> R.string.style_mono
        StylePreset.MONO_CONTRAST -> R.string.style_mono_contrast
    }
