package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MonitorPrefsTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/settings-test").apply { mkdirs() })

    private val file by lazy { File(folder.root, "settings.preferences_pb") }
    private val jobs = mutableListOf<Job>()

    private fun open(): DataStoreSettingsRepository {
        val job = Job().also { jobs += it }
        return DataStoreSettingsRepository.create(CoroutineScope(Dispatchers.IO + job)) { file }
    }

    @After
    fun tearDown() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test
    fun freshInstallHasEverythingOff() = runBlocking {
        assertEquals(MonitorPrefs.DEFAULT, open().monitorPrefs.first())
    }

    @Test
    fun choicesSurviveARestart() = runBlocking {
        val prefs = MonitorPrefs(
            zebras = true,
            zebraLevel = 80,
            skinZebras = true,
            falseColor = true,
            peaking = true,
            peakingColor = PeakingColor.GREEN,
            scope = MonitorScope.WAVEFORM,
            frameLines = FrameLines.SCOPE_2_39,
            thirds = true,
            centre = true,
            desqueeze = DesqueezeFactor.X1_33
        )
        open().setMonitorPrefs(prefs)
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
        val reopened = open()
        assertEquals(prefs, reopened.monitorPrefs.first())
        // Additive keys: the app settings are untouched.
        assertEquals(AppSettings.DEFAULT, reopened.settings.first())
    }

    @Test
    fun unknownOrInvalidValuesFallBackToDefaults() = runBlocking {
        val repo = open()
        repo.dataStore.edit {
            it[stringPreferencesKey("monitor_scope")] = "oscilloscope"
            it[stringPreferencesKey("monitor_peaking_color")] = "ultraviolet"
            it[intPreferencesKey("monitor_zebra_level")] = 500
        }
        val prefs = repo.monitorPrefs.first()
        assertEquals(MonitorScope.NONE, prefs.scope)
        assertEquals(PeakingColor.RED, prefs.peakingColor)
        assertEquals(MonitorPrefs.DEFAULT_ZEBRA_LEVEL, prefs.zebraLevel)
    }

    @Test
    fun resetClearsTheMonitorChoices() = runBlocking {
        val repo = open()
        repo.setMonitorPrefs(MonitorPrefs(peaking = true))
        repo.resetToDefaults()
        assertEquals(MonitorPrefs.DEFAULT, repo.monitorPrefs.first())
    }
}
