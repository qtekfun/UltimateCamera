package com.qtekfun.ultimatecamera.core.recording

/**
 * Names of the files a portrait photo keeps for editing after capture (D88), next to the photo: the unblurred camera
 * photo `…_P<nnn>_sharp.jpg` and the depth with its real range `…_P<nnn>_depth16.png` ([com.qtekfun.ultimatecamera.core.gpu.portrait.DepthPng16]).
 * Edits are new files `…_P<nnn>_e<n>.jpg` (never an overwrite). Restyled (`_s<n>`) and edited (`_e<n>`) copies of a
 * photo share its edit files: the names are taken from the photo's root.
 */
object PortraitFiles {
    private val COPY_SUFFIXES = Regex("(_[es]\\d+)+$")

    /** `UC_…_P001_e2_s1.jpg` → `UC_…_P001`: the shot's own name without extension and copy suffixes. */
    fun root(photo: String): String {
        val dot = photo.lastIndexOf('.')
        val stem = if (dot > 0) photo.substring(0, dot) else photo
        return stem.replace(COPY_SUFFIXES, "")
    }

    /** The unblurred photo of [photo]'s shot. */
    fun sharpName(photo: String): String = root(photo) + "_sharp" + extension(photo)

    /** The 16-bit depth of [photo]'s shot. */
    fun depthName(photo: String): String = root(photo) + "_depth16.png"

    /** The first `…_e<n>` edit of [photo]'s shot not in [taken] (compared ignoring case, as storage does). */
    fun editedName(photo: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        val root = root(photo)
        val extension = extension(photo)
        var n = 1
        while ("${root}_e$n$extension".lowercase() in lower) n++
        return "${root}_e$n$extension"
    }

    private fun extension(photo: String): String {
        val dot = photo.lastIndexOf('.')
        return if (dot > 0) photo.substring(dot) else ".jpg"
    }
}
