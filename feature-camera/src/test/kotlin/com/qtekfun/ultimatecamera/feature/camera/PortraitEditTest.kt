package com.qtekfun.ultimatecamera.feature.camera

import com.qtekfun.ultimatecamera.core.camera.ImagePoint
import com.qtekfun.ultimatecamera.core.gpu.portrait.DepthPng16
import com.qtekfun.ultimatecamera.core.recording.PortraitFiles
import com.qtekfun.ultimatecamera.core.recording.PortraitLook
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
class PortraitEditTest {
    private val photo = SavedPhoto(RecordingLocation.Default, "UC_20261018_101500_CAM0_P007.jpg")

    /** Shot upright with the phone held portrait: sensor turned 90° clockwise; captured at f/2.8 focused on (0.3, 0.6). */
    private val source = PortraitSource(
        PortraitFiles.sharpName(photo.name),
        PortraitFiles.depthName(photo.name),
        DepthPng16.Meta(rotation = 90, fNumber = 2.8f, focusX = 0.3f, focusY = 0.6f)
    )

    /** Previews are "f<n>@x,y" strings; saves are recorded. */
    private class FakeStore(var source: PortraitSource?, val files: MutableSet<String>) : PortraitEditStore<String> {
        val previews = mutableListOf<PortraitLook>()
        val saves = mutableListOf<PortraitLook>()
        var failFind = false
        var failPreview = false
        var failSave = false
        var released = 0

        override suspend fun find(photo: SavedPhoto): PortraitSource? = if (failFind) throw IOException("gone") else source

        override suspend fun preview(photo: SavedPhoto, source: PortraitSource, look: PortraitLook): String {
            if (failPreview) throw IOException("no memory")
            previews += look
            return "f${look.fNumber}@${look.focusX},${look.focusY}"
        }

        override suspend fun save(photo: SavedPhoto, source: PortraitSource, look: PortraitLook): String {
            if (failSave) throw IOException("disk full")
            saves += look
            return PortraitFiles.editedName(photo.name, files).also { files += it }
        }

        override fun release() {
            released++
        }
    }

    private fun TestScope.opened(src: PortraitSource? = source): Pair<PortraitEdit<String>, FakeStore> {
        val store = FakeStore(src, mutableSetOf(photo.name))
        val edit = PortraitEdit(this, store)
        edit.open(photo)
        assertTrue(edit.state.value.loading)
        advanceUntilIdle()
        return edit to store
    }

    @Test
    fun anEditableShotStartsFromTheCapturesApertureAndFocusAndRendersOnlyWhenShown() = runTest {
        val (edit, store) = opened()
        val ui = edit.state.value
        assertTrue(ui.available)
        assertEquals(3, ui.aperture) // f/2.8
        assertEquals(ImagePoint(0.3f, 0.6f), ui.focus)
        assertFalse("nothing changed yet", ui.canSave)
        assertNull("no work until the portrait tab is shown", ui.preview)
        assertTrue(store.previews.isEmpty())

        edit.show()
        advanceUntilIdle()
        assertEquals("f2.8@0.3,0.6", edit.state.value.preview)
        edit.show() // Already has one: no second render.
        advanceUntilIdle()
        assertEquals(1, store.previews.size)
    }

    @Test
    fun aTapOnTheUprightPhotoMovesTheFocusInTheSensorFrameAndTheRingFollows() = runTest {
        val (edit, store) = opened()
        // Upright (0.9, 0.2) on a 90° shot is sensor (0.2, 0.1): the photo's top right is the sensor's top left.
        edit.tapUpright(0.9f, 0.2f)
        val focus = edit.state.value.focus
        assertEquals(0.2f, focus.x, 1e-6f)
        assertEquals(0.1f, focus.y, 1e-6f)
        assertEquals(0.9f, edit.state.value.focusUpright.first, 1e-6f)
        assertEquals(0.2f, edit.state.value.focusUpright.second, 1e-6f)
        assertTrue(edit.state.value.canSave)
        advanceUntilIdle()
        val look = store.previews.single()
        assertEquals(0.2f, look.focusX, 1e-6f)
        assertEquals(0.1f, look.focusY, 1e-6f)
        assertEquals(2.8f, look.fNumber, 0f)
        assertFalse("an edit never writes edit files of its own", look.keepEdit || look.saveDepth)
    }

    @Test
    fun sliderMovesAreDebouncedAndTheNewestWins() = runTest {
        val (edit, store) = opened()
        edit.setAperture(1)
        advanceTimeBy(PortraitEdit.PREVIEW_DEBOUNCE_MS / 2)
        edit.setAperture(8)
        advanceTimeBy(PortraitEdit.PREVIEW_DEBOUNCE_MS / 2)
        runCurrent()
        assertTrue("still waiting for the drag to rest", store.previews.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(16f), store.previews.map { it.fNumber })
        assertEquals("f16.0@0.3,0.6", edit.state.value.preview)
        edit.setAperture(99)
        assertEquals("clamped to the last stop", 8, edit.state.value.aperture)
        edit.setAperture(0)
        advanceUntilIdle()
        assertEquals(0f, store.previews.last().fNumber, 0f)
    }

    @Test
    fun savingWritesNewNumberedFilesWithTheChosenLook() = runTest {
        val (edit, store) = opened()
        edit.setAperture(1)
        edit.tapUpright(0.5f, 0.5f)
        edit.save()
        assertTrue(edit.state.value.saving)
        advanceUntilIdle()
        assertEquals("UC_20261018_101500_CAM0_P007_e1.jpg", edit.state.value.saved)
        assertEquals(1.4f, store.saves.single().fNumber, 0f)
        assertEquals(0.5f, store.saves.single().focusX, 1e-6f)
        edit.setAperture(2)
        edit.save()
        advanceUntilIdle()
        assertEquals("UC_20261018_101500_CAM0_P007_e2.jpg", edit.state.value.saved)
        assertTrue(photo.name in store.files)
    }

    @Test
    fun theCapturesOwnLookIsNotSavedAgain() = runTest {
        val (edit, store) = opened()
        edit.setAperture(5)
        edit.setAperture(3)
        assertFalse(edit.state.value.canSave)
        edit.save()
        advanceUntilIdle()
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun aShotWithoutEditFilesIsNotEditable() = runTest {
        val (edit, store) = opened(src = null)
        assertFalse(edit.state.value.available)
        assertFalse(edit.state.value.loading)
        edit.show()
        edit.setAperture(1)
        edit.tapUpright(0.1f, 0.1f)
        edit.save()
        advanceUntilIdle()
        assertTrue(store.previews.isEmpty() && store.saves.isEmpty())
        assertEquals(PortraitEditUi.CENTRE, edit.state.value.focus)
    }

    @Test
    fun failuresBecomeTheNoteAndNeverAFile() = runTest {
        val store = FakeStore(source, mutableSetOf(photo.name)).apply { failFind = true }
        val edit = PortraitEdit(this, store)
        edit.open(photo)
        advanceUntilIdle()
        assertFalse("an unreadable depth is just not editable", edit.state.value.available)

        val (ok, okStore) = opened()
        okStore.failPreview = true
        ok.show()
        advanceUntilIdle()
        assertTrue(ok.state.value.failed)
        assertFalse(ok.state.value.rendering)
        okStore.failSave = true
        ok.setAperture(1)
        ok.save()
        advanceUntilIdle()
        assertTrue(ok.state.value.failed)
        assertNull(ok.state.value.saved)
        assertEquals(setOf(photo.name), okStore.files)
    }

    @Test
    fun closeFreesThePreview() = runTest {
        val (edit, store) = opened()
        edit.show()
        advanceUntilIdle()
        edit.close()
        assertNull(edit.state.value.photo)
        assertNull(edit.state.value.preview)
        assertEquals(1, store.released)
    }

    @Test
    fun aperturePositionsComeFromTheRecordedFNumber() {
        assertEquals(0, PortraitEditUi.position(0f))
        assertEquals(1, PortraitEditUi.position(1.4f))
        assertEquals(3, PortraitEditUi.position(2.8f))
        assertEquals(3, PortraitEditUi.position(2.9f))
        assertEquals(8, PortraitEditUi.position(22f))
    }

    @Test
    fun tapsMapOntoTheFittedPhotoAndNotTheBars() {
        // A 400 x 300 box showing a 300 x 400 (portrait) photo: scaled to 225 x 300, bars of 87.5 left and right.
        assertNull(PortraitEditUi.tapOnFitted(50f, 150f, 400f, 300f, 300, 400))
        val (u, v) = requireNotNull(PortraitEditUi.tapOnFitted(200f, 75f, 400f, 300f, 300, 400))
        assertEquals(0.5f, u, 1e-6f)
        assertEquals(0.25f, v, 1e-6f)
        val (x, y) = requireNotNull(PortraitEditUi.onFitted(u, v, 400f, 300f, 300, 400))
        assertEquals(200f, x, 1e-4f)
        assertEquals(75f, y, 1e-4f)
        assertNull(PortraitEditUi.tapOnFitted(1f, 1f, 0f, 300f, 300, 400))
        assertNull(PortraitEditUi.onFitted(0.5f, 0.5f, 400f, 300f, 0, 400))
    }
}
