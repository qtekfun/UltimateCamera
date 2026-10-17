package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsBackupTest {
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/settings-test").apply { mkdirs() })

    private val jobs = mutableListOf<Job>()

    private fun open(name: String): DataStoreSettingsRepository {
        val job = Job().also { jobs += it }
        return DataStoreSettingsRepository.create(CoroutineScope(Dispatchers.IO + job)) { File(folder.root, "$name.preferences_pb") }
    }

    @After
    fun tearDown() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test
    fun exportListsEveryTypedKeyButTheSchema() = runBlocking {
        val repo = open("a")
        repo.setMaxBrightness(true)
        repo.setAudioGainDb(6f)
        repo.setLook(LookSlot.RECORDING, "lib:abc")
        repo.setHdrPrefs(HdrPrefs(hdrViewfinder = false))
        repo.dataStore.edit {
            it[longPreferencesKey("future_long")] = 7L
            it[stringSetPreferencesKey("future_set")] = setOf("x", "y")
        }
        val values = repo.backup!!.exportValues()
        assertEquals(SettingValue.Bool(true), values["max_brightness"])
        assertEquals(SettingValue.FloatValue(6f), values["audio_gain_db"])
        assertEquals(SettingValue.Text("lib:abc"), values["recording_look"])
        assertEquals(SettingValue.Bool(false), values["hdr_viewfinder"])
        assertEquals(SettingValue.LongValue(7L), values["future_long"])
        assertEquals(SettingValue.TextSet(setOf("x", "y")), values["future_set"])
        assertFalse("schema_version" in values)
    }

    @Test
    fun roundTripToAnotherStoreGivesTheSameSettings() = runBlocking {
        val source = open("source")
        source.setMaxBrightness(true)
        source.setSeparateWav(true)
        source.setMonitorPrefs(MonitorPrefs(zebras = true, zebraLevel = 90, peaking = true))
        source.setTimecodeMode(TimecodeMode.TIME_OF_DAY)
        val target = open("target")
        target.backup!!.restoreValues(source.backup!!.exportValues(), replace = true)
        assertEquals(source.settings.first(), target.settings.first())
        assertEquals(source.monitorPrefs.first(), target.monitorPrefs.first())
        assertEquals(source.captureModePrefs.first(), target.captureModePrefs.first())
        assertEquals(DataStoreSettingsRepository.SCHEMA_VERSION, target.storedSchemaVersion())
    }

    @Test
    fun deviceSpecificKeysAreSkippedAndKeptOnThisPhone() = runBlocking {
        val source = open("source")
        source.setRecordingTreeUri("content://other/tree")
        source.setAudioInputKey("usb:Other mic")
        source.setMaxBrightness(true)
        val target = open("target")
        target.setAudioInputKey("usb:My mic")
        target.setSeparateWav(true)
        val skipped = target.backup!!.restoreValues(source.backup!!.exportValues(), replace = true)
        assertTrue("recording_tree_uri" in skipped && "audio_input_key" in skipped)
        val settings = target.settings.first()
        assertNull(settings.recordingTreeUri)
        assertEquals("usb:My mic", settings.audioInputKey)
        assertTrue(settings.maxBrightness)
        assertFalse("replace drops keys the backup lacks", settings.separateWav)
    }

    @Test
    fun mergeKeepsKeysTheBackupLacks() = runBlocking {
        val source = open("source")
        source.setMaxBrightness(true)
        val target = open("target")
        target.setSeparateWav(true)
        target.backup!!.restoreValues(source.backup!!.exportValues(), replace = false)
        val settings = target.settings.first()
        assertTrue(settings.maxBrightness)
        assertTrue(settings.separateWav)
    }

    @Test
    fun aValueOfAnotherTypeReplacesTheOldOne() = runBlocking {
        val target = open("target")
        target.setMaxBrightness(true)
        target.backup!!.restoreValues(mapOf("max_brightness" to SettingValue.Text("yes")), replace = false)
        assertEquals(SettingValue.Text("yes"), target.backup!!.exportValues()["max_brightness"])
        assertFalse("a value of the wrong type reads as the default", target.settings.first().maxBrightness)
    }
}
