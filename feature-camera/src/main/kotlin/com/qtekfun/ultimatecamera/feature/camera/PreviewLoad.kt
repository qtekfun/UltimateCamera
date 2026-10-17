package com.qtekfun.ultimatecamera.feature.camera

import android.os.PowerManager
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeKind
import com.qtekfun.ultimatecamera.core.recording.PhoneTier

/**
 * How much the live preview may cost (D68). The take is never touched: only the screen refresh, the preview surface
 * size and the scopes.
 *
 * @property everyNthFrame the preview shows one camera frame in this many.
 * @property maxLongSide the preview surface's long side is capped to this many pixels (0: the view's own size).
 * @property scopeUpdateEvery scope analysis every n-th preview frame.
 * @property scopes false hides the scope (overlays stay: they are what the operator exposes and focuses with).
 */
data class PreviewLoad(val everyNthFrame: Int, val maxLongSide: Int, val scopeUpdateEvery: Int, val scopes: Boolean) {
    /** The monitor settings the renderer gets under this load. */
    fun applyTo(settings: MonitorSettings): MonitorSettings = if (scopes) {
        settings.copy(scopeUpdateEvery = scopeUpdateEvery)
    } else {
        settings.copy(scope = ScopeKind.NONE, scopeUpdateEvery = scopeUpdateEvery)
    }

    companion object {
        val FULL = PreviewLoad(everyNthFrame = 1, maxLongSide = 0, scopeUpdateEvery = MonitorSettings.SCOPE_UPDATE_EVERY, scopes = true)
    }
}

/** Picks the [PreviewLoad] from the phone's tier and heat (REQ-NFR-001, D68). Pure. */
object PreviewLoadPolicy {
    /** Long side of the preview surface on a modest or hot phone: 720p class, plenty for a phone screen's monitor. */
    const val LIGHT_LONG_SIDE = 1280

    /** Scope analysis on a modest or hot phone: every 8th frame (7.5 Hz at 60 fps). */
    const val LIGHT_SCOPE_EVERY = 8

    /** Preview at half rate when hot. */
    const val HOT_EVERY_NTH = 2

    /**
     * Hot = thermal status MODERATE or worse, or the headroom forecast reaching SEVERE within its window ([severeSoon],
     * a thermal alert of [HealthForecast], which already holds for a few seconds after it clears).
     */
    fun load(tier: PhoneTier, thermalStatus: Int, severeSoon: Boolean): PreviewLoad {
        val hot = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || severeSoon
        return when {
            hot -> PreviewLoad(everyNthFrame = HOT_EVERY_NTH, maxLongSide = LIGHT_LONG_SIDE, scopeUpdateEvery = LIGHT_SCOPE_EVERY, scopes = false)
            tier == PhoneTier.MODEST -> PreviewLoad(everyNthFrame = 1, maxLongSide = LIGHT_LONG_SIDE, scopeUpdateEvery = LIGHT_SCOPE_EVERY, scopes = true)
            else -> PreviewLoad.FULL
        }
    }

    /** True when [health] carries a thermal alert (the forecast reaches SEVERE within its window, D52). */
    fun severeSoon(health: HealthState): Boolean = health.alerts.any { it.kind == HealthKind.THERMAL }
}

/** The preview surface's buffer size for a view of [width]×[height] under a long-side cap; null keeps the view's own. */
object PreviewSizeCap {
    fun cap(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int>? {
        val long = maxOf(width, height)
        val laidOut = width > 0 && height > 0
        if (maxLongSide <= 0 || !laidOut || long <= maxLongSide) return null
        val scale = maxLongSide.toDouble() / long

        // Even sizes: some compositors round odd buffer sizes and the letterbox would move by a pixel.
        fun even(v: Double) = maxOf(2, v.toInt() - v.toInt() % 2)
        return even(width * scale) to even(height * scale)
    }
}
