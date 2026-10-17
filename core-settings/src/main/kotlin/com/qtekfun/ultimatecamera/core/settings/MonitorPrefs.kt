package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow

/** Peaking colours offered in the camera screen. [key] is persisted: never rename it. */
enum class PeakingColor(val key: String, val argb: Int) {
    RED("red", 0xFFFF2828.toInt()),
    GREEN("green", 0xFF28E628.toInt()),
    BLUE("blue", 0xFF3C82FF.toInt()),
    YELLOW("yellow", 0xFFFFE628.toInt()),
    WHITE("white", 0xFFFFFFFF.toInt())
}

/** The scope shown on the preview (one at a time). [key] is persisted. */
enum class MonitorScope(val key: String) {
    NONE("none"),
    HISTOGRAM("histogram"),
    WAVEFORM("waveform"),
    VECTORSCOPE("vectorscope")
}

/** Frame lines drawn over the preview, by target aspect ratio (null = none). [key] is persisted. */
enum class FrameLines(val key: String, val aspect: Float?) {
    NONE("none", null),
    SCOPE_2_39("2.39", 2.39f),
    FLAT_1_85("1.85", 1.85f),
    WIDE_16_9("16:9", 16f / 9f),
    TV_4_3("4:3", 4f / 3f),
    SQUARE_1_1("1:1", 1f)
}

/** Anamorphic desqueeze of the preview only. [key] is persisted. */
enum class DesqueezeFactor(val key: String, val factor: Float) {
    NONE("none", 1f),
    X1_33("1.33", 1.33f),
    X1_5("1.5", 1.5f),
    X1_8("1.8", 1.8f),
    X2("2", 2f)
}

/**
 * The monitoring tools the user turned on in the camera screen (REQ-MON-001), persisted so they survive a restart.
 * They only affect the preview, never the recording. Defaults: everything off, highlight zebras at 95 %.
 */
data class MonitorPrefs(
    val zebras: Boolean = false,
    /** Lower edge of the highlight zebra, in percent of the monitored signal (D33). */
    val zebraLevel: Int = DEFAULT_ZEBRA_LEVEL,
    /** Second zebra on the 65–75 % skin window. */
    val skinZebras: Boolean = false,
    val falseColor: Boolean = false,
    val peaking: Boolean = false,
    val peakingColor: PeakingColor = PeakingColor.RED,
    val scope: MonitorScope = MonitorScope.NONE,
    val frameLines: FrameLines = FrameLines.NONE,
    val thirds: Boolean = false,
    val centre: Boolean = false,
    val desqueeze: DesqueezeFactor = DesqueezeFactor.NONE
) {
    companion object {
        const val DEFAULT_ZEBRA_LEVEL = 95

        /** Highlight zebra levels offered in the UI. */
        val ZEBRA_LEVELS = listOf(70, 80, 90, 95, 100)
        val DEFAULT = MonitorPrefs()
    }
}

/** Read and write [MonitorPrefs]; implemented by [DataStoreSettingsRepository] with additive keys (no schema bump). */
interface MonitorPrefsRepository {
    val monitorPrefs: Flow<MonitorPrefs>

    suspend fun setMonitorPrefs(prefs: MonitorPrefs)
}

/** Key layout of [MonitorPrefs] in the settings file. Absent or unreadable values mean their defaults. */
internal object MonitorPrefsKeys {
    private val ZEBRAS = booleanPreferencesKey("monitor_zebras")
    private val ZEBRA_LEVEL = intPreferencesKey("monitor_zebra_level")
    private val SKIN_ZEBRAS = booleanPreferencesKey("monitor_skin_zebras")
    private val FALSE_COLOR = booleanPreferencesKey("monitor_false_color")
    private val PEAKING = booleanPreferencesKey("monitor_peaking")
    private val PEAKING_COLOR = stringPreferencesKey("monitor_peaking_color")
    private val SCOPE = stringPreferencesKey("monitor_scope")
    private val FRAME_LINES = stringPreferencesKey("monitor_frame_lines")
    private val THIRDS = booleanPreferencesKey("monitor_thirds")
    private val CENTRE = booleanPreferencesKey("monitor_centre")
    private val DESQUEEZE = stringPreferencesKey("monitor_desqueeze")

    fun read(preferences: Preferences): MonitorPrefs {
        val stored = preferences.asMap()
        val d = MonitorPrefs.DEFAULT
        fun flag(key: Preferences.Key<Boolean>, default: Boolean) = stored[key] as? Boolean ?: default
        fun text(key: Preferences.Key<String>) = stored[key] as? String
        return MonitorPrefs(
            zebras = flag(ZEBRAS, d.zebras),
            zebraLevel = (stored[ZEBRA_LEVEL] as? Int)?.takeIf { it in 0..ZEBRA_LEVEL_MAX } ?: d.zebraLevel,
            skinZebras = flag(SKIN_ZEBRAS, d.skinZebras),
            falseColor = flag(FALSE_COLOR, d.falseColor),
            peaking = flag(PEAKING, d.peaking),
            peakingColor = PeakingColor.entries.firstOrNull { it.key == text(PEAKING_COLOR) } ?: d.peakingColor,
            scope = MonitorScope.entries.firstOrNull { it.key == text(SCOPE) } ?: d.scope,
            frameLines = FrameLines.entries.firstOrNull { it.key == text(FRAME_LINES) } ?: d.frameLines,
            thirds = flag(THIRDS, d.thirds),
            centre = flag(CENTRE, d.centre),
            desqueeze = DesqueezeFactor.entries.firstOrNull { it.key == text(DESQUEEZE) } ?: d.desqueeze
        )
    }

    fun write(preferences: MutablePreferences, prefs: MonitorPrefs) {
        preferences[ZEBRAS] = prefs.zebras
        preferences[ZEBRA_LEVEL] = prefs.zebraLevel
        preferences[SKIN_ZEBRAS] = prefs.skinZebras
        preferences[FALSE_COLOR] = prefs.falseColor
        preferences[PEAKING] = prefs.peaking
        preferences[PEAKING_COLOR] = prefs.peakingColor.key
        preferences[SCOPE] = prefs.scope.key
        preferences[FRAME_LINES] = prefs.frameLines.key
        preferences[THIRDS] = prefs.thirds
        preferences[CENTRE] = prefs.centre
        preferences[DESQUEEZE] = prefs.desqueeze.key
    }

    private const val ZEBRA_LEVEL_MAX = 109
}
