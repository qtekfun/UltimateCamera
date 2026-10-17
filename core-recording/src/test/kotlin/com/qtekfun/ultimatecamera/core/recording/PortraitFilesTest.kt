package com.qtekfun.ultimatecamera.core.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class PortraitFilesTest {
    private val photo = "UC_20261018_101500_CAM0_P007.jpg"

    @Test
    fun theEditFilesSitNextToThePhoto() {
        assertEquals("UC_20261018_101500_CAM0_P007_sharp.jpg", PortraitFiles.sharpName(photo))
        assertEquals("UC_20261018_101500_CAM0_P007_depth16.png", PortraitFiles.depthName(photo))
        // Not the editors' 8-bit map of D73.
        assertEquals("UC_20261018_101500_CAM0_P007_depth.png", PhotoController.depthName(photo))
    }

    @Test
    fun restyledAndEditedCopiesShareTheShotsEditFiles() {
        for (copy in listOf("UC_20261018_101500_CAM0_P007_s2.jpg", "UC_20261018_101500_CAM0_P007_e1.jpg", "UC_20261018_101500_CAM0_P007_e3_s1.jpg")) {
            assertEquals(copy, "UC_20261018_101500_CAM0_P007", PortraitFiles.root(copy))
            assertEquals("UC_20261018_101500_CAM0_P007_sharp.jpg", PortraitFiles.sharpName(copy))
        }
        // A suffix that only looks like one in the middle of the name stays.
        assertEquals("UC_s1_P001", PortraitFiles.root("UC_s1_P001.jpg"))
    }

    @Test
    fun editsAreNewFilesNumberedFromTheShotNeverAnOverwrite() {
        assertEquals("UC_20261018_101500_CAM0_P007_e1.jpg", PortraitFiles.editedName(photo, setOf(photo)))
        val taken = setOf(photo, "UC_20261018_101500_CAM0_P007_e1.jpg", "uc_20261018_101500_cam0_p007_E2.JPG")
        assertEquals("UC_20261018_101500_CAM0_P007_e3.jpg", PortraitFiles.editedName(photo, taken))
        // An edit of an edit, or of a restyled copy, numbers on from the shot.
        assertEquals("UC_20261018_101500_CAM0_P007_e3.jpg", PortraitFiles.editedName("UC_20261018_101500_CAM0_P007_e1_s1.jpg", taken))
        assertEquals("noext_e1.jpg", PortraitFiles.editedName("noext", emptySet()))
    }
}
