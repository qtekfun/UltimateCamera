package com.qtekfun.ultimatecamera.feature.camera

/**
 * The look baked into the recording (REQ-COL-002/004). Stored in settings as [token]; an unknown token reads as [None].
 */
sealed interface RecordingLook {
    val token: String?

    /** The camera's own signal (HLG in 10-bit, BT.709 in 8-bit). */
    data object None : RecordingLook {
        override val token: String? = null
    }

    /** UC-Log from the 10-bit HLG signal (D29); on 8-bit modes it falls back to [None]. */
    data object UcLog : RecordingLook {
        override val token = "uclog"
    }

    /** A LUT of the user's library, baked into the file. */
    data class Library(val id: String) : RecordingLook {
        override val token = "$LIBRARY_PREFIX$id"
    }

    companion object {
        fun parse(token: String?): RecordingLook = when {
            token == UcLog.token -> UcLog
            token != null && token.startsWith(LIBRARY_PREFIX) && token.length > LIBRARY_PREFIX.length -> Library(token.removePrefix(LIBRARY_PREFIX))
            else -> None
        }
    }
}

/** The look shown only on the preview, on top of what is recorded (REQ-COL-004). Unknown tokens read as [Auto]. */
sealed interface MonitorLook {
    val token: String?

    /** Nothing extra, except the UC-Log → Rec.709 viewing LUT when recording UC-Log. */
    data object Auto : MonitorLook {
        override val token: String? = null
    }

    /** Exactly what is recorded (flat Log stays flat). */
    data object None : MonitorLook {
        override val token = "none"
    }

    /** The built-in UC-Log → Rec.709 viewing LUT (D29). */
    data object UcLogToRec709 : MonitorLook {
        override val token = "uclog709"
    }

    data class Library(val id: String) : MonitorLook {
        override val token = "$LIBRARY_PREFIX$id"
    }

    companion object {
        fun parse(token: String?): MonitorLook = when {
            token == None.token -> None
            token == UcLogToRec709.token -> UcLogToRec709
            token != null && token.startsWith(LIBRARY_PREFIX) && token.length > LIBRARY_PREFIX.length -> Library(token.removePrefix(LIBRARY_PREFIX))
            else -> Auto
        }
    }
}

private const val LIBRARY_PREFIX = "lib:"

/** A LUT the pipeline needs: generated in the app or loaded from the library. */
sealed interface LutRef {
    data object UcLogToRec709 : LutRef

    data class Library(val id: String) : LutRef
}

/**
 * What the GPU stage must do for a pair of choices: [ucLog] converts the camera signal before both LUTs, [record] is
 * baked into the file and [monitor] is the chain the preview shows (applied in order; empty = no LUT). The preview always
 * starts from what is recorded, so a baked LUT is first in the monitor chain.
 */
data class LookPlan(val ucLog: Boolean, val record: LutRef?, val monitor: List<LutRef>) {
    companion object {
        fun of(recording: RecordingLook, monitor: MonitorLook, tenBit: Boolean): LookPlan {
            val effective = if (recording == RecordingLook.UcLog && !tenBit) RecordingLook.None else recording
            val record = (effective as? RecordingLook.Library)?.let { LutRef.Library(it.id) }
            val extra: LutRef? = when (monitor) {
                MonitorLook.Auto -> if (effective == RecordingLook.UcLog) LutRef.UcLogToRec709 else null
                MonitorLook.None -> null
                MonitorLook.UcLogToRec709 -> LutRef.UcLogToRec709
                is MonitorLook.Library -> LutRef.Library(monitor.id)
            }
            return LookPlan(ucLog = effective == RecordingLook.UcLog, record = record, monitor = listOfNotNull(record, extra))
        }
    }
}
