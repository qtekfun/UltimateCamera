package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import com.qtekfun.ultimatecamera.core.recording.RestylePlan
import com.qtekfun.ultimatecamera.core.storage.RecordingLocation
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoRestyleTest {
    private val photo = SavedPhoto(RecordingLocation.Default, "UC_20261017_120000_CAM0_P001.jpg")
    private val base = "UC_20261017_120000_CAM0_P001_base.jpg"
    private val warm = StyleParams.of(StylePreset.WARM)
    private val mono = StyleParams(StylePreset.MONO, tone = 0.3f, intensity = 0.6f)

    /** Previews are "<source>|<token>" strings; saves are recorded. */
    private class FakeStore(var info: StyleXmp.Info, val files: MutableSet<String>) : RestyleStore<String> {
        val previews = mutableListOf<String>()
        val saves = mutableListOf<Triple<String, StyleParams, String?>>()
        var failSave = false
        var failInspect = false
        var released = 0

        override suspend fun inspect(photo: SavedPhoto): StyleXmp.Info = if (failInspect) throw IOException("gone") else info

        override suspend fun exists(photo: SavedPhoto, name: String) = name in files

        override suspend fun preview(photo: SavedPhoto, source: String, style: StyleParams): String = "$source|${style.token}".also { previews += it }

        override suspend fun save(photo: SavedPhoto, source: String, style: StyleParams, base: String?): String {
            if (failSave) throw IOException("disk full")
            saves += Triple(source, style, base)
            return StyleXmp.restyledName(photo.name, files).also { files += it }
        }

        override fun release() {
            released++
        }
    }

    private fun TestScope.opened(info: StyleXmp.Info, vararg files: String): Pair<PhotoRestyle<String>, FakeStore> {
        val store = FakeStore(info, files.toMutableSet())
        val restyle = PhotoRestyle(this, store)
        restyle.open(photo)
        assertTrue(restyle.state.value.loading)
        advanceUntilIdle()
        return restyle to store
    }

    @Test
    fun aStyledPhotoWithItsCopyRestylesFromTheCopyAndSavesANewFile() = runTest {
        val (restyle, store) = opened(StyleXmp.Info(warm, base = base, foreignXmp = false), photo.name, base)
        val ui = restyle.state.value
        assertEquals(RestylePlan.FromBase(base), ui.plan)
        assertEquals(warm, ui.original)
        // It opens showing the photo as it is: its own style on the copy.
        assertEquals("$base|${warm.token}", ui.preview)
        assertFalse("nothing changed yet", ui.canSave)

        restyle.setStyle(mono)
        advanceUntilIdle()
        assertEquals("$base|${mono.token}", restyle.state.value.preview)
        assertTrue(restyle.state.value.canSave)

        restyle.save()
        assertTrue(restyle.state.value.saving)
        advanceUntilIdle()
        // Pixels from the copy, the copy named as the new file's base, the original untouched.
        assertEquals(listOf(Triple(base, mono, base)), store.saves)
        assertEquals("UC_20261017_120000_CAM0_P001_s1.jpg", restyle.state.value.saved)
        assertFalse(restyle.state.value.saving)
        assertEquals(photo, restyle.state.value.photo)
    }

    @Test
    fun aStyledPhotoWithoutCopyExplainsAndCannotBeTuned() = runTest {
        val (restyle, store) = opened(StyleXmp.Info(warm, base = null, foreignXmp = false), photo.name)
        val ui = restyle.state.value
        assertEquals(RestylePlan.NeedsBase, ui.plan)
        // The photo is shown as it is, nothing applied on top.
        assertEquals("${photo.name}|${RestyleUi.STANDARD.token}", ui.preview)
        restyle.setStyle(mono)
        restyle.save()
        advanceUntilIdle()
        assertEquals(warm, restyle.state.value.style)
        assertFalse(restyle.state.value.canSave)
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun anUnstyledPhotoIsRestyledFromItselfAndBecomesTheNewFilesBase() = runTest {
        val (restyle, store) = opened(StyleXmp.Info.NONE, photo.name)
        assertEquals(RestylePlan.FromPhoto, restyle.state.value.plan)
        assertEquals(RestyleUi.STANDARD, restyle.state.value.original)
        restyle.setStyle(warm)
        advanceUntilIdle()
        restyle.save()
        advanceUntilIdle()
        assertEquals(listOf(Triple(photo.name, warm, photo.name)), store.saves)
    }

    @Test
    fun anUltraHdrPhotoWithoutStyleRecordIsUnknown() = runTest {
        val (restyle, _) = opened(StyleXmp.Info(style = null, base = null, foreignXmp = true), photo.name)
        assertEquals(RestylePlan.Unknown, restyle.state.value.plan)
        assertFalse(restyle.state.value.canSave)
    }

    @Test
    fun aDragMakesOnePreviewAndTheNewestWins() = runTest {
        val (restyle, store) = opened(StyleXmp.Info(warm, base = base, foreignXmp = false), base)
        store.previews.clear()
        val steps = (1..10).map { StyleParams(StylePreset.WARM, tone = it / 10f) }
        steps.forEach {
            restyle.setStyle(it)
            advanceTimeBy(PhotoRestyle.PREVIEW_DEBOUNCE_MS / 3)
        }
        runCurrent()
        assertTrue("debounced while dragging", store.previews.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("$base|${steps.last().token}"), store.previews)
        assertEquals(steps.last(), restyle.state.value.style)
    }

    @Test
    fun failuresAreShownAndLeaveNoHalfState() = runTest {
        val (restyle, store) = opened(StyleXmp.Info(warm, base = base, foreignXmp = false), base)
        store.failSave = true
        restyle.setStyle(mono)
        advanceUntilIdle()
        restyle.save()
        advanceUntilIdle()
        assertTrue(restyle.state.value.failed)
        assertFalse(restyle.state.value.saving)
        assertNull(restyle.state.value.saved)
        // Changing the style again clears the note.
        restyle.setStyle(warm.copy(tone = 0.5f))
        assertFalse(restyle.state.value.failed)

        val broken = FakeStore(StyleXmp.Info.NONE, mutableSetOf()).apply { failInspect = true }
        val other = PhotoRestyle(this, broken)
        other.open(photo)
        advanceUntilIdle()
        assertTrue(other.state.value.failed)
        assertFalse(other.state.value.loading)
        assertFalse(other.state.value.canSave)
    }

    @Test
    fun closingClearsTheViewerAndFreesThePreview() = runTest {
        val (restyle, store) = opened(StyleXmp.Info(warm, base = base, foreignXmp = false), base)
        restyle.close()
        assertNull(restyle.state.value.photo)
        assertNull(restyle.state.value.preview)
        assertEquals(1, store.released)
        // Nothing to tune or save once closed.
        restyle.setStyle(mono)
        restyle.save()
        advanceUntilIdle()
        assertTrue(store.saves.isEmpty())
    }
}
