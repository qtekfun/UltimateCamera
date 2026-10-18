package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp

/**
 * Where a restyle of a saved photo takes its pixels from, or why it cannot (D79 follow-up). Styles are
 * not stacked: a new style is applied to unstyled pixels only, so the result is what the shot would have been with it.
 */
sealed interface RestylePlan {
    /** The unstyled copy [base] (Settings › Styles › Keep an unstyled copy) is there: any style can be applied to it. */
    data class FromBase(val base: String) : RestylePlan

    /** The photo itself carries no style (Standard): styling it is the same as styling at capture. */
    data object FromPhoto : RestylePlan

    /** The photo is styled and there is no unstyled copy: a new style would stack on the old one. */
    data object NeedsBase : RestylePlan

    /** The photo's style is not recorded (an Ultra HDR photo keeps only its gain map XMP) and there is no copy. */
    data object Unknown : RestylePlan

    /** True when a new styled JPEG can be made. */
    val possible: Boolean get() = this is FromBase || this == FromPhoto

    companion object {
        /** The unstyled copy [photo] would have: the one its XMP names, else the `…_base.jpg` next to it. */
        fun baseCandidate(photo: String, info: StyleXmp.Info): String = info.base ?: StyleXmp.baseName(photo)

        /**
         * The plan for [photo] whose XMP says [info], [baseExists] telling whether [baseCandidate] is in storage. A copy
         * wins whenever it exists (even when the style was not recorded); without one only an unstyled photo can be
         * restyled.
         */
        fun of(info: StyleXmp.Info, baseExists: Boolean, baseName: String): RestylePlan {
            val style = info.style
            return when {
                baseExists -> FromBase(baseName)
                style == null && !info.foreignXmp -> FromPhoto
                style != null && style.isIdentity -> FromPhoto
                style != null -> NeedsBase
                else -> Unknown
            }
        }

        /**
         * The file the pixels come from, and the unstyled copy the new file names in its XMP so it can be restyled
         * again: the base, or for an unstyled photo the photo itself. Null when the plan is not [possible].
         */
        fun source(plan: RestylePlan, photo: String): String? = when (plan) {
            is FromBase -> plan.base
            FromPhoto -> photo
            NeedsBase, Unknown -> null
        }
    }
}
