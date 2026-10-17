package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreSettingsRepositoryTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/settings-test").apply { mkdirs() })

    private val file by lazy { File(folder.root, "settings.preferences_pb") }
    private val jobs = mutableListOf<Job>()

    private fun open(): DataStoreSettingsRepository {
        val job = Job().also { jobs += it }
        return DataStoreSettingsRepository.create(CoroutineScope(Dispatchers.IO + job)) { file }
    }

    /** Closes the open instance so the same file can be opened again, as after a process restart. */
    private suspend fun reopen(): DataStoreSettingsRepository {
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
        return open()
    }

    @After
    fun tearDown() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test
    fun freshInstallYieldsDefaults() = runBlocking {
        val settings = open().settings.first()
        assertEquals(AppSettings.DEFAULT, settings)
        assertFalse(settings.maxBrightness)
    }

    @Test
    fun defaultGroupsAreTheShippedOnes() {
        val expected = setOf(
            FeatureGroup.MANUAL_CONTROLS,
            FeatureGroup.PROFILES,
            FeatureGroup.METERS,
            FeatureGroup.LUTS,
            FeatureGroup.ADVANCED_AUDIO
        )
        assertEquals(expected, AppSettings.DEFAULT.enabledGroups)
        assertFalse(AppSettings.DEFAULT.isEnabled(FeatureGroup.MULTICAMERA))
        assertFalse(AppSettings.DEFAULT.isEnabled(FeatureGroup.REMOTE_MONITOR))
    }

    @Test
    fun persistedKeysAreUniqueAndStable() {
        val keys = FeatureGroup.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("meters", FeatureGroup.METERS.key)
    }

    @Test
    fun maxBrightnessRoundTripsThroughDisk() = runBlocking {
        open().setMaxBrightness(true)
        assertTrue(reopen().settings.first().maxBrightness)
        reopen().setMaxBrightness(false)
        assertFalse(reopen().settings.first().maxBrightness)
    }

    @Test
    fun volumeKeysRecordIsOnByDefaultAndRoundTripsThroughDisk() = runBlocking {
        assertTrue(open().settings.first().volumeKeysRecord)
        reopen().setControlOption(ControlOption.VOLUME_KEYS_RECORD, false)
        assertFalse(reopen().settings.first().volumeKeysRecord)
        reopen().setControlOption(ControlOption.VOLUME_KEYS_RECORD, true)
        assertTrue(reopen().settings.first().volumeKeysRecord)
    }

    @Test
    fun alertVibrationIsOnByDefaultAndRoundTripsThroughDisk() = runBlocking {
        assertTrue(open().settings.first().alertVibration)
        reopen().setControlOption(ControlOption.ALERT_VIBRATION, false)
        assertFalse(reopen().settings.first().alertVibration)
    }

    @Test
    fun everyGroupRoundTripsThroughDisk() = runBlocking {
        var repository = open()
        for (group in FeatureGroup.entries) {
            repository.setFeatureGroupEnabled(group, !group.defaultEnabled)
            repository = reopen()
            val toggled = repository.settings.first()
            assertEquals(group.name, !group.defaultEnabled, toggled.isEnabled(group))
            assertEquals(FeatureGroup.defaults.symmetricDifference(setOf(group)), toggled.enabledGroups)

            repository.setFeatureGroupEnabled(group, group.defaultEnabled)
            repository = reopen()
            assertEquals(FeatureGroup.defaults, repository.settings.first().enabledGroups)
        }
    }

    @Test
    fun togglingAGroupLeavesTheOthersAlone() = runBlocking {
        val repository = open()
        repository.setFeatureGroupEnabled(FeatureGroup.METERS, false)
        repository.setFeatureGroupEnabled(FeatureGroup.MULTICAMERA, true)
        repository.setMaxBrightness(true)
        val settings = repository.settings.first()
        assertEquals(FeatureGroup.defaults - FeatureGroup.METERS + FeatureGroup.MULTICAMERA, settings.enabledGroups)
        assertTrue(settings.maxBrightness)
    }

    @Test
    fun resetRestoresDefaults() = runBlocking {
        val repository = open()
        FeatureGroup.entries.forEach { repository.setFeatureGroupEnabled(it, !it.defaultEnabled) }
        repository.setMaxBrightness(true)
        repository.resetToDefaults()
        assertEquals(AppSettings.DEFAULT, repository.settings.first())
        assertEquals(AppSettings.DEFAULT, reopen().settings.first())
    }

    @Test
    fun corruptFileIsReplacedByDefaults() = runBlocking {
        file.writeBytes(byteArrayOf(0x0A, 0x7F, 0x13, 0x00, 0x42) + "not a protobuf".toByteArray())
        val repository = open()
        assertEquals(AppSettings.DEFAULT, repository.settings.first())

        repository.setMaxBrightness(true)
        assertTrue(reopen().settings.first().maxBrightness)
    }

    @Test
    fun valuesOfTheWrongTypeFallBackToDefaults() = runBlocking {
        open().dataStore.edit {
            it[stringPreferencesKey("max_brightness")] = "yes"
            it[intPreferencesKey("group_multicamera")] = 1
            it[stringPreferencesKey("group_from_a_newer_version")] = "kept"
        }
        val repository = reopen()
        assertEquals(AppSettings.DEFAULT, repository.settings.first())

        repository.setMaxBrightness(true)
        assertTrue(repository.settings.first().maxBrightness)
        val unknown = repository.dataStore.data.first()[stringPreferencesKey("group_from_a_newer_version")]
        assertEquals("kept", unknown)
    }

    @Test
    fun writesStoreTheSchemaVersion() = runBlocking {
        val repository = open()
        assertNull(repository.storedSchemaVersion())
        repository.setMaxBrightness(true)
        assertEquals(DataStoreSettingsRepository.SCHEMA_VERSION, repository.storedSchemaVersion())
        repository.resetToDefaults()
        assertEquals(DataStoreSettingsRepository.SCHEMA_VERSION, repository.storedSchemaVersion())
    }

    @Test
    fun filesWithoutSchemaVersionAreMigrated() = runBlocking {
        // Written straight to the store, bypassing the repository: what a pre-schema file looks like.
        open().dataStore.edit { it[DataStoreSettingsRepository.MAX_BRIGHTNESS] = true }
        val migrated = reopen()
        assertEquals(DataStoreSettingsRepository.SCHEMA_VERSION, migrated.storedSchemaVersion())
        assertTrue(migrated.settings.first().maxBrightness)
    }

    @Test
    fun recordingTreeUriRoundTripsThroughDiskAndClears() = runBlocking {
        val uri = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AFootage"
        assertNull(open().settings.first().recordingTreeUri)
        reopen().setRecordingTreeUri(uri)
        assertEquals(uri, reopen().settings.first().recordingTreeUri)
        reopen().setRecordingTreeUri(null)
        assertNull(reopen().settings.first().recordingTreeUri)
    }

    @Test
    fun recordingTreeUriIsDroppedByResetAndIgnoredWhenMalformed() = runBlocking {
        val repository = open()
        repository.setRecordingTreeUri("content://x/tree/primary%3AMovies")
        repository.resetToDefaults()
        assertNull(repository.settings.first().recordingTreeUri)

        repository.dataStore.edit { it[booleanPreferencesKey("recording_tree_uri")] = true }
        assertNull(reopen().settings.first().recordingTreeUri)
        reopen().dataStore.edit { it[DataStoreSettingsRepository.RECORDING_TREE_URI] = "  " }
        assertNull(reopen().settings.first().recordingTreeUri)
    }

    @Test
    fun autoConfigOfferedDefaultsOffPersistsAndResets() = runBlocking {
        assertFalse(open().settings.first().autoConfigOffered)
        reopen().setAutoConfigOffered(true)
        assertTrue(reopen().settings.first().autoConfigOffered)
        reopen().resetToDefaults()
        assertFalse(reopen().settings.first().autoConfigOffered)
    }

    @Test
    fun looksRoundTripThroughDiskClearAndReset() = runBlocking {
        val fresh = open().settings.first()
        assertNull(fresh.recordingLook)
        assertNull(fresh.monitorLook)
        reopen().setLook(LookSlot.RECORDING, "lib:abc")
        reopen().setLook(LookSlot.MONITOR, "uclog709")
        reopen().settings.first().let {
            assertEquals("lib:abc", it.recordingLook)
            assertEquals("uclog709", it.monitorLook)
        }
        reopen().setLook(LookSlot.MONITOR, null)
        assertNull(reopen().settings.first().monitorLook)
        assertEquals("lib:abc", reopen().settings.first().recordingLook)
        reopen().resetToDefaults()
        assertNull(reopen().settings.first().recordingLook)
        reopen().dataStore.edit { it[booleanPreferencesKey("recording_look")] = true }
        assertNull(reopen().settings.first().recordingLook)
    }

    @Test
    fun recordingLocationModeRoundTripsAndResets() = runBlocking {
        assertNull(open().settings.first().recordingLocationMode)
        reopen().setRecordingLocationMode("volume:1a2b-3c4d")
        assertEquals("volume:1a2b-3c4d", reopen().settings.first().recordingLocationMode)
        reopen().setRecordingLocationMode(" ")
        assertNull(reopen().settings.first().recordingLocationMode)
        reopen().setRecordingLocationMode("phone")
        reopen().resetToDefaults()
        assertNull(reopen().settings.first().recordingLocationMode)
    }

    @Test
    fun audioSettingsRoundTripThroughDiskAndReset() = runBlocking {
        val fresh = open().settings.first()
        assertNull(fresh.audioInputKey)
        assertEquals(0f, fresh.audioGainDb, 0f)
        assertFalse(fresh.separateWav)
        reopen().setAudioInputKey("USB:Rode AI-Micro")
        reopen().setAudioGainDb(12.5f)
        reopen().setSeparateWav(true)
        val stored = reopen().settings.first()
        assertEquals("USB:Rode AI-Micro", stored.audioInputKey)
        assertEquals(12.5f, stored.audioGainDb, 0f)
        assertTrue(stored.separateWav)
        reopen().resetToDefaults()
        assertEquals(AppSettings.DEFAULT, reopen().settings.first())
    }

    @Test
    fun audioGainIsClampedAndCorruptValuesFallBack() = runBlocking {
        val repository = open()
        repository.setAudioGainDb(99f)
        assertEquals(AppSettings.AUDIO_GAIN_MAX_DB, repository.settings.first().audioGainDb, 0f)
        repository.setAudioGainDb(-99f)
        assertEquals(AppSettings.AUDIO_GAIN_MIN_DB, repository.settings.first().audioGainDb, 0f)
        repository.dataStore.edit { it[DataStoreSettingsRepository.AUDIO_GAIN_DB] = Float.NaN }
        assertEquals(0f, reopen().settings.first().audioGainDb, 0f)
        reopen().setAudioInputKey(" ")
        assertNull(reopen().settings.first().audioInputKey)
    }

    private fun <T> Set<T>.symmetricDifference(other: Set<T>): Set<T> = (this - other) + (other - this)
}
