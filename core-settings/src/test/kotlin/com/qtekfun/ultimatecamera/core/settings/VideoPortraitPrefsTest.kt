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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoPortraitPrefsTest {
    // Under the module's build dir, on disk (the dev machine's /tmp is tmpfs).
    @get:Rule
    val folder = TemporaryFolder(File("build/tmp/video-portrait-test").apply { mkdirs() })

    private val job = Job()

    @After
    fun tearDown(): Unit = runBlocking { job.cancelAndJoin() }

    @Test
    fun videoBlurIsOffByDefault() {
        assertFalse(DataStoreVideoPortraitRepository.read(mutablePreferencesOf()))
    }

    @Test
    fun aStoredSwitchReadsBack() {
        assertTrue(DataStoreVideoPortraitRepository.read(mutablePreferencesOf(DataStoreVideoPortraitRepository.VIDEO_PORTRAIT to true)))
        assertFalse(DataStoreVideoPortraitRepository.read(mutablePreferencesOf(DataStoreVideoPortraitRepository.VIDEO_PORTRAIT to false)))
    }

    @Test
    fun aValueOfTheWrongTypeIsOff() {
        assertFalse(DataStoreVideoPortraitRepository.read(mutablePreferencesOf(stringPreferencesKey("video_portrait") to "yes")))
    }

    @Test
    fun otherRepositoriesHaveNoVideoBlurSwitch() {
        val fake = java.lang.reflect.Proxy.newProxyInstance(
            SettingsRepository::class.java.classLoader,
            arrayOf(SettingsRepository::class.java)
        ) { _, _, _ -> error("not used") } as SettingsRepository
        assertNull(fake.videoPortrait)
    }

    @Test
    fun anUnreadableStoreMeansOff(): Unit = runBlocking {
        val file = File(folder.root, "broken.preferences_pb").apply { writeBytes(byteArrayOf(0x7F, 0x01, 0x02, 0x03)) }
        val settings = DataStoreSettingsRepository.create(CoroutineScope(Dispatchers.IO + job)) { file }
        assertFalse(requireNotNull(settings.videoPortrait).videoPortraitEnabled.first())
    }

    @Test
    fun theSwitchRoundTripsThroughTheSettingsStore(): Unit = runBlocking {
        val file = File(folder.root, "settings.preferences_pb")
        val settings = DataStoreSettingsRepository.create(CoroutineScope(Dispatchers.IO + job)) { file }
        val portrait = settings.videoPortrait
        assertNotNull(portrait)
        requireNotNull(portrait)
        assertFalse(portrait.videoPortraitEnabled.first())
        portrait.setVideoPortraitEnabled(true)
        assertTrue(portrait.videoPortraitEnabled.first())
        portrait.setVideoPortraitEnabled(false)
        assertFalse(portrait.videoPortraitEnabled.first())
    }
}
