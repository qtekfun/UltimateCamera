package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoContainerPrefsTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/container-test").apply { mkdirs() })

    private val jobs = mutableListOf<Job>()
    private val file by lazy { File(folder.root, "settings.preferences_pb") }

    private fun open(): DataStoreSettingsRepository {
        val job = Job().also { jobs += it }
        return DataStoreSettingsRepository.create(CoroutineScope(Dispatchers.IO + job)) { file }
    }

    @After
    fun tearDown() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test
    fun absentUnknownOrWrongTypeMeansMp4() {
        assertEquals(VideoContainerChoice.MP4, DataStoreVideoContainerRepository.read(mutablePreferencesOf()))
        assertEquals(VideoContainerChoice.MP4, DataStoreVideoContainerRepository.read(mutablePreferencesOf(stringPreferencesKey("video_container") to "mkv")))
        assertEquals(VideoContainerChoice.MP4, DataStoreVideoContainerRepository.read(mutablePreferencesOf(intPreferencesKey("video_container") to 1)))
    }

    @Test
    fun persistedKeysAreStable() {
        assertEquals(listOf("mp4", "mov"), VideoContainerChoice.entries.map { it.key })
    }

    @Test
    fun movRoundTripsThroughDiskAndKeepsTheSchema() = runBlocking {
        val repository = open()
        val container = requireNotNull(repository.videoContainer)
        assertEquals(VideoContainerChoice.MP4, container.videoContainer.first())
        container.setVideoContainer(VideoContainerChoice.MOV)
        assertEquals(VideoContainerChoice.MOV, container.videoContainer.first())
        assertEquals(DataStoreSettingsRepository.SCHEMA_VERSION, repository.storedSchemaVersion())
    }

    @Test
    fun anUnreadableFileMeansMp4() = runBlocking {
        file.writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37))
        assertEquals(VideoContainerChoice.MP4, requireNotNull(open().videoContainer).videoContainer.first())
    }

    @Test
    fun otherRepositoriesHaveNoContainerPreference() {
        val fake = java.lang.reflect.Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader,
            arrayOf(SettingsRepository::class.java)
        ) { _, _, _ -> error("not used") } as SettingsRepository
        assertNull(fake.videoContainer)
    }
}
