package com.qtekfun.ultimatecamera.core.recording

import java.io.File

/**
 * The scratch directory of our own HDR fusion (`cacheDir/hdr`): three I420 frames (≈54 MB at 12 MP) and the fused
 * JPEG. A shot that was interrupted (app killed, camera lost, encoder failure) must not leave them behind, so the
 * directory is wiped when the photo mode first opens ([clear]) and before and after every shot ([session]).
 */
class HdrWorkDir(val dir: File) {
    /** Deletes every file in [dir]; returns how many were removed. */
    fun clear(): Int = dir.listFiles()?.count { it.isFile && it.delete() } ?: 0

    /** Runs [block] on a freshly emptied [dir] and empties it again however [block] ends. */
    inline fun <T> session(block: (File) -> T): T {
        clear()
        dir.mkdirs()
        try {
            return block(dir)
        } finally {
            clear()
        }
    }

    companion object {
        const val FRAME_SUFFIX = ".i420"
    }
}
