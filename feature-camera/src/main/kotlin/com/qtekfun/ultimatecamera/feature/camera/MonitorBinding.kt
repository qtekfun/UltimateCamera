package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.gpu.meters.Desqueeze
import com.qtekfun.ultimatecamera.core.gpu.meters.FalseColorSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.GuideSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorOverlaySettings
import com.qtekfun.ultimatecamera.core.gpu.meters.MonitorSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.PeakingSettings
import com.qtekfun.ultimatecamera.core.gpu.meters.ScopeKind
import com.qtekfun.ultimatecamera.core.gpu.meters.ZebraSettings
import com.qtekfun.ultimatecamera.core.settings.DesqueezeFactor
import com.qtekfun.ultimatecamera.core.settings.FeatureGroup
import com.qtekfun.ultimatecamera.core.settings.MonitorPrefs
import com.qtekfun.ultimatecamera.core.settings.MonitorPrefsRepository
import com.qtekfun.ultimatecamera.core.settings.MonitorScope
import com.qtekfun.ultimatecamera.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the monitor panel shows: [available] is false while `FeatureGroup.METERS` is disabled (panel hidden). */
data class MonitorUiState(val available: Boolean = false, val prefs: MonitorPrefs = MonitorPrefs.DEFAULT)

/** Turns the user's choices into what the renderer draws. Pure, so it is unit-tested. */
object MonitorMapping {
    /** Upper edge of the highlight zebra: above 100 % so clipped white is always striped (D33). */
    private const val ZEBRA_MAX = 110f
    private const val SKIN_MIN = 65f
    private const val SKIN_MAX = 75f

    fun toSettings(state: MonitorUiState): MonitorSettings = if (state.available) toSettings(state.prefs) else MonitorSettings.OFF

    fun toSettings(prefs: MonitorPrefs): MonitorSettings {
        val zebraDefaults = ZebraSettings()
        val zebras = ZebraSettings(
            primary = zebraDefaults.primary.copy(enabled = prefs.zebras, minPercent = prefs.zebraLevel.toFloat(), maxPercent = ZEBRA_MAX),
            secondary = zebraDefaults.secondary.copy(enabled = prefs.skinZebras, minPercent = SKIN_MIN, maxPercent = SKIN_MAX)
        )
        val overlay = MonitorOverlaySettings(
            peaking = PeakingSettings(enabled = prefs.peaking, color = prefs.peakingColor.argb),
            zebras = zebras,
            falseColor = FalseColorSettings(enabled = prefs.falseColor),
            guides = GuideSettings(thirds = prefs.thirds, centre = prefs.centre, frameLineAspect = prefs.frameLines.aspect),
            desqueeze = desqueeze(prefs.desqueeze)
        )
        return MonitorSettings(overlay, scope(prefs.scope))
    }

    private fun desqueeze(factor: DesqueezeFactor) = when (factor) {
        DesqueezeFactor.NONE -> Desqueeze.NONE
        DesqueezeFactor.X1_33 -> Desqueeze.X1_33
        DesqueezeFactor.X1_5 -> Desqueeze.X1_5
        DesqueezeFactor.X1_8 -> Desqueeze.X1_8
        DesqueezeFactor.X2 -> Desqueeze.X2
    }

    private fun scope(scope: MonitorScope) = when (scope) {
        MonitorScope.NONE -> ScopeKind.NONE
        MonitorScope.HISTOGRAM -> ScopeKind.HISTOGRAM
        MonitorScope.WAVEFORM -> ScopeKind.WAVEFORM
        MonitorScope.VECTORSCOPE -> ScopeKind.VECTORSCOPE
    }
}

/**
 * Keeps the monitoring tools of the camera screen: follows `FeatureGroup.METERS` and the persisted [MonitorPrefs],
 * and hands every change to [apply] (the camera controller). Lives in the view model's [scope].
 */
class MonitorBinding(private val settings: SettingsRepository, private val scope: CoroutineScope, private val apply: (MonitorSettings) -> Unit) {
    private val repository = settings as? MonitorPrefsRepository
    private val _state = MutableStateFlow(MonitorUiState())
    private var lastApplied: MonitorSettings? = null
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    init {
        scope.launch { settings.settings.collect { s -> publish { it.copy(available = s.isEnabled(FeatureGroup.METERS)) } } }
        repository?.let { repo -> scope.launch { repo.monitorPrefs.collect { p -> publish { it.copy(prefs = p) } } } }
    }

    /** Applies [prefs] at once and persists them. */
    fun set(prefs: MonitorPrefs) {
        publish { it.copy(prefs = prefs) }
        repository?.let { repo -> scope.launch { repo.setMonitorPrefs(prefs) } }
    }

    private fun publish(change: (MonitorUiState) -> MonitorUiState) {
        _state.update(change)
        val settings = MonitorMapping.toSettings(_state.value)
        if (settings != lastApplied) {
            lastApplied = settings
            apply(settings)
        }
    }
}
