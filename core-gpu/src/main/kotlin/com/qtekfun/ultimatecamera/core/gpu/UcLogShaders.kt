package com.qtekfun.ultimatecamera.core.gpu

import java.util.Locale

/**
 * GLSL ES 3.00 functions for UC-Log, generated from the constants in [UcLog] so the shader and the CPU reference cannot
 * drift apart. Needs `precision highp float`. Functions: `ucLogEncode(vec3 linear)`, `ucLogDecode(vec3 signal)`,
 * `hlgToLinear(vec3)`, `bt709ToLinear(vec3)`, `ucLogFromHlg(vec3)` and `ucLogFromBt709(vec3)`.
 */
object UcLogShaders {
    val FUNCTIONS: String = """
        const float UC_GREY_SIGNAL = ${UcLog.GREY_SIGNAL.glsl()};
        const float UC_STOP_SLOPE = ${UcLog.STOP_SLOPE.glsl()};
        const float UC_OFFSET = ${UcLog.OFFSET.glsl()};
        const float UC_ANCHOR = ${(UcLog.GREY + UcLog.OFFSET).glsl()};
        const float UC_BLACK = ${UcLog.BLACK_SIGNAL.glsl()};
        const float UC_TOE_SLOPE = ${UcLog.TOE_SLOPE.glsl()};
        const float UC_HLG_SCALE = ${UcLog.HLG_SCALE.glsl()};
        vec3 ucLogEncode(vec3 x) {
            vec3 logPart = UC_GREY_SIGNAL + UC_STOP_SLOPE * log2((max(x, 0.0) + UC_OFFSET) / UC_ANCHOR);
            vec3 toe = UC_BLACK + UC_TOE_SLOPE * x;
            return mix(toe, logPart, step(0.0, x));
        }
        vec3 ucLogDecode(vec3 y) {
            vec3 logPart = UC_ANCHOR * exp2((y - UC_GREY_SIGNAL) / UC_STOP_SLOPE) - UC_OFFSET;
            vec3 toe = (y - UC_BLACK) / UC_TOE_SLOPE;
            return mix(toe, logPart, step(UC_BLACK, y));
        }
        vec3 hlgToLinear(vec3 e) {
            e = max(e, 0.0);
            vec3 low = e * e / 3.0;
            vec3 high = (exp((e - ${UcLog.HLG_C.glsl()}) / ${UcLog.HLG_A.glsl()}) + ${UcLog.HLG_B.glsl()}) / 12.0;
            return mix(low, high, step(0.5000001, e));
        }
        vec3 bt709ToLinear(vec3 v) {
            v = max(v, 0.0);
            vec3 low = v / 4.5;
            vec3 high = pow((v + 0.099) / 1.099, vec3(1.0 / 0.45));
            return mix(low, high, step(${(UcLog.BT709_BREAK * 4.5).glsl()}, v));
        }
        vec3 ucLogFromHlg(vec3 c) { return ucLogEncode(hlgToLinear(c) * UC_HLG_SCALE); }
        vec3 ucLogFromBt709(vec3 c) { return ucLogEncode(bt709ToLinear(c)); }
    """.trimIndent()

    /** The GLSL call that turns the camera signal [source] into UC-Log. */
    fun call(source: LogSource, arg: String): String = when (source) {
        LogSource.HLG -> "ucLogFromHlg($arg)"
        LogSource.BT709 -> "ucLogFromBt709($arg)"
    }

    private fun Float.glsl(): String = String.format(Locale.ROOT, "%.9g", this)

    private fun Double.glsl(): String = String.format(Locale.ROOT, "%.9g", this)
}
