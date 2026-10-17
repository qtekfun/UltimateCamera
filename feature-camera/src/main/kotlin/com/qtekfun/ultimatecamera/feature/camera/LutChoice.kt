package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.gpu.style.StyleInput
import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams

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

    /** A style (D74) baked into the file; on 10-bit modes it is applied after HLG → UC-Log → Rec.709. */
    data class Style(val params: StyleParams) : RecordingLook {
        override val token = params.token
    }

    companion object {
        fun parse(token: String?): RecordingLook = when {
            token == UcLog.token -> UcLog
            StyleParams.parse(token) != null -> Style(requireNotNull(StyleParams.parse(token)))
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

    /** A style (D74) on top of what is recorded, after the viewing transform when that is Log or HLG. */
    data class Style(val params: StyleParams) : MonitorLook {
        override val token = params.token
    }

    companion object {
        fun parse(token: String?): MonitorLook = when {
            token == None.token -> None
            StyleParams.parse(token) != null -> Style(requireNotNull(StyleParams.parse(token)))
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

    /** A generated style LUT for [input] ([com.qtekfun.ultimatecamera.core.gpu.style.StyleEngine.lutFor]). */
    data class Style(val params: StyleParams, val input: StyleInput) : LutRef
}

/**
 * What the GPU stage must do for a pair of choices: [ucLog] converts the camera signal before both LUTs, [record] is
 * baked into the file and [monitor] is the chain the preview shows (applied in order; empty = no LUT). The preview always
 * starts from what is recorded, so a baked LUT is first in the monitor chain.
 *
 * Styles (D74) work on display-referred Rec.709: a recording style on a 10-bit mode turns the UC-Log stage on and bakes
 * UC-Log → Rec.709 → style (so the file is Rec.709, tagged so by D53); a monitor style follows the viewing transform of
 * what is recorded (none after a baked LUT, UC-Log → Rec.709 after UC-Log, HLG → Rec.709 after the plain 10-bit signal).
 * [defaultStyle] (Settings › Styles) stands in for the recording look "none" when it is not the identity.
 */
data class LookPlan(val ucLog: Boolean, val record: LutRef?, val monitor: List<LutRef>) {
    companion object {
        fun of(recording: RecordingLook, monitor: MonitorLook, tenBit: Boolean, defaultStyle: StyleParams? = null): LookPlan {
            val effective = effective(recording, tenBit, defaultStyle)
            val record: LutRef? = when (effective) {
                is RecordingLook.Library -> LutRef.Library(effective.id)
                is RecordingLook.Style -> LutRef.Style(effective.params, if (tenBit) StyleInput.UC_LOG else StyleInput.REC709)
                else -> null
            }
            val ucLog = effective == RecordingLook.UcLog || (effective is RecordingLook.Style && tenBit)
            val styleInput = when {
                record != null || !tenBit -> StyleInput.REC709
                ucLog -> StyleInput.UC_LOG
                else -> StyleInput.HLG
            }
            return LookPlan(ucLog = ucLog, record = record, monitor = listOfNotNull(record, extra(monitor, effective, styleInput)))
        }

        /** What is really recorded: the default style for none, nothing for UC-Log on 8-bit or an identity style. */
        private fun effective(recording: RecordingLook, tenBit: Boolean, defaultStyle: StyleParams?): RecordingLook {
            val chosen = if (recording == RecordingLook.None && defaultStyle != null) RecordingLook.Style(defaultStyle) else recording
            return when {
                chosen == RecordingLook.UcLog && !tenBit -> RecordingLook.None
                chosen is RecordingLook.Style && chosen.params.isIdentity -> RecordingLook.None
                else -> chosen
            }
        }

        private fun extra(monitor: MonitorLook, effective: RecordingLook, styleInput: StyleInput): LutRef? = when (monitor) {
            MonitorLook.Auto -> if (effective == RecordingLook.UcLog) LutRef.UcLogToRec709 else null
            MonitorLook.None -> null
            MonitorLook.UcLogToRec709 -> LutRef.UcLogToRec709
            is MonitorLook.Library -> LutRef.Library(monitor.id)
            is MonitorLook.Style -> if (monitor.params.isIdentity) null else LutRef.Style(monitor.params, styleInput)
        }
    }
}
