package com.qtekfun.ultimatecamera.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
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

class StylePrefsTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/style-prefs-test").apply { mkdirs() })

    private val jobs = mutableListOf<Job>()

    private fun open(): DataStoreSettingsRepository {
        val job = Job().also { jobs += it }
        return DataStoreSettingsRepository.create(CoroutineScope(Dispatchers.IO + job)) { File(folder.root, "settings.preferences_pb") }
    }

    @After
    fun tearDown() = runBlocking { jobs.forEach { it.cancelAndJoin() } }

    @Test
    fun absentMeansStandardWithoutCopyAndKeysAreStable() {
        assertEquals(StyleDefaults(), DataStoreStyleRepository.read(mutablePreferencesOf()))
        val stored = mutablePreferencesOf(
            stringPreferencesKey("photo_style") to "style:warm",
            stringPreferencesKey("video_style") to "style:mono",
            booleanPreferencesKey("photo_style_keep_base") to true
        )
        assertEquals(StyleDefaults("style:warm", "style:mono", keepBase = true), DataStoreStyleRepository.read(stored))
    }

    @Test
    fun defaultsRoundTripThroughDiskAndKeepTheSchema() = runBlocking {
        val repository = open()
        val styles = requireNotNull(repository.styles)
        styles.setPhotoStyle("style:vibrant:0.20,0.00,0.00,1.00")
        styles.setVideoStyle("style:soft_film")
        styles.setKeepBase(true)
        assertEquals(StyleDefaults("style:vibrant:0.20,0.00,0.00,1.00", "style:soft_film", true), styles.styleDefaults.first())
        styles.setVideoStyle(null)
        assertNull(styles.styleDefaults.first().video)
        assertEquals(DataStoreSettingsRepository.SCHEMA_VERSION, repository.storedSchemaVersion())
    }

    @Test
    fun otherRepositoriesHaveNoStyles() {
        val fake = java.lang.reflect.Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader,
            arrayOf(SettingsRepository::class.java)
        ) { _, _, _ -> error("not used") } as SettingsRepository
        assertNull(fake.styles)
    }
}
