package com.qtekfun.ultimatecamera.core.settings

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

class PhotoHdrPrefsTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/photo-hdr-test").apply { mkdirs() })

    private val jobs = mutableListOf<Job>()

    private fun open(): DataStoreSettingsRepository {
        val job = Job().also { jobs += it }
        return DataStoreSettingsRepository.create(CoroutineScope(Dispatchers.IO + job)) { File(folder.root, "settings.preferences_pb") }
    }

    @After
    fun tearDown() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test
    fun absentOrUnknownValueMeansAuto() {
        assertEquals(PhotoHdrDefault.AUTO, DataStorePhotoHdrRepository.read(mutablePreferencesOf()))
        val unknown = mutablePreferencesOf(stringPreferencesKey("photo_hdr_default") to "always")
        assertEquals(PhotoHdrDefault.AUTO, DataStorePhotoHdrRepository.read(unknown))
    }

    @Test
    fun persistedKeysAreStable() {
        assertEquals(listOf("auto", "off"), PhotoHdrDefault.entries.map { it.key })
    }

    @Test
    fun offRoundTripsThroughDiskAndKeepsTheSchema() = runBlocking {
        val repository = open()
        val hdr = requireNotNull(repository.photoHdr)
        assertEquals(PhotoHdrDefault.AUTO, hdr.photoHdrDefault.first())
        hdr.setPhotoHdrDefault(PhotoHdrDefault.OFF)
        assertEquals(PhotoHdrDefault.OFF, hdr.photoHdrDefault.first())
        assertEquals(DataStoreSettingsRepository.SCHEMA_VERSION, repository.storedSchemaVersion())
    }

    @Test
    fun otherRepositoriesHaveNoPhotoHdrPreference() {
        val fake = java.lang.reflect.Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader,
            arrayOf(SettingsRepository::class.java)
        ) { _, _, _ -> error("not used") } as SettingsRepository
        assertNull(fake.photoHdr)
    }
}
