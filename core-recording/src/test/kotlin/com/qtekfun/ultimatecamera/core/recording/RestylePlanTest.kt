package com.qtekfun.ultimatecamera.core.recording

import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.gpu.style.StyleXmp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestylePlanTest {
    private val photo = "UC_20261017_120000_CAM0_P001.jpg"
    private val vibrant = StyleParams(StylePreset.VIBRANT, tone = 0.2f)

    @Test
    fun theCopyNamedInTheXmpWinsAndIsAlsoTheNewFilesBase() {
        val info = StyleXmp.Info(vibrant, base = "elsewhere_base.jpg", foreignXmp = false)
        assertEquals("elsewhere_base.jpg", RestylePlan.baseCandidate(photo, info))
        val plan = RestylePlan.of(info, baseExists = true, baseName = "elsewhere_base.jpg")
        assertEquals(RestylePlan.FromBase("elsewhere_base.jpg"), plan)
        assertTrue(plan.possible)
        assertEquals("elsewhere_base.jpg", RestylePlan.source(plan, photo))
    }

    @Test
    fun withoutANameTheConventionalCopyIsLookedFor() {
        val info = StyleXmp.Info(vibrant, base = null, foreignXmp = false)
        assertEquals("UC_20261017_120000_CAM0_P001_base.jpg", RestylePlan.baseCandidate(photo, info))
    }

    @Test
    fun aStyledPhotoWithoutCopyNeedsOneInsteadOfStackingStyles() {
        val plan = RestylePlan.of(StyleXmp.Info(vibrant, base = "x_base.jpg", foreignXmp = false), baseExists = false, baseName = "x_base.jpg")
        assertEquals(RestylePlan.NeedsBase, plan)
        assertFalse(plan.possible)
        assertNull(RestylePlan.source(plan, photo))
    }

    @Test
    fun anUnstyledPhotoIsItsOwnSource() {
        // No XMP at all: the camera's or our render without a style (Standard).
        val plan = RestylePlan.of(StyleXmp.Info.NONE, baseExists = false, baseName = "b")
        assertEquals(RestylePlan.FromPhoto, plan)
        assertEquals(photo, RestylePlan.source(plan, photo))
        // A recorded identity style (Standard at its defaults, or intensity 0) is unstyled too.
        val identity = StyleXmp.Info(StyleParams(StylePreset.MONO, intensity = 0f), base = null, foreignXmp = false)
        assertEquals(RestylePlan.FromPhoto, RestylePlan.of(identity, baseExists = false, baseName = "b"))
        val standard = StyleXmp.Info(StyleParams.of(StylePreset.STANDARD), base = null, foreignXmp = false)
        assertEquals(RestylePlan.FromPhoto, RestylePlan.of(standard, baseExists = false, baseName = "b"))
        // A tuned Standard is a style: it would stack.
        val tuned = StyleXmp.Info(StyleParams(StylePreset.STANDARD, tone = 0.3f), base = null, foreignXmp = false)
        assertEquals(RestylePlan.NeedsBase, RestylePlan.of(tuned, baseExists = false, baseName = "b"))
    }

    @Test
    fun anUltraHdrPhotoWithoutStyleRecordIsUnknownUnlessItsCopyExists() {
        val ultraHdr = StyleXmp.Info(style = null, base = null, foreignXmp = true)
        val unknown = RestylePlan.of(ultraHdr, baseExists = false, baseName = "p_base.jpg")
        assertEquals(RestylePlan.Unknown, unknown)
        assertFalse(unknown.possible)
        assertNull(RestylePlan.source(unknown, photo))
        assertEquals(RestylePlan.FromBase("p_base.jpg"), RestylePlan.of(ultraHdr, baseExists = true, baseName = "p_base.jpg"))
    }
}
