package com.qtekfun.ultimatecamera.core.gpu.meters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideLayoutTest {
    private val full = PixelRect(0, 0, 1920, 1080)

    private fun rects(settings: GuideSettings, area: PixelRect = full): List<PixelRect> {
        val out = GuideRects()
        GuideLayout.layout(settings, area, out)
        return (0 until out.count).map { PixelRect(out.rects[it * 4], out.rects[it * 4 + 1], out.rects[it * 4 + 2], out.rects[it * 4 + 3]) }
    }

    @Test
    fun nothingEnabledDrawsNothing() = assertEquals(emptyList<PixelRect>(), rects(GuideSettings()))

    @Test
    fun thirdsAreCentredOnTheThirdLines() {
        assertEquals(
            listOf(PixelRect(639, 0, 2, 1080), PixelRect(0, 359, 1920, 2), PixelRect(1279, 0, 2, 1080), PixelRect(0, 719, 1920, 2)),
            rects(GuideSettings(thirds = true))
        )
    }

    @Test
    fun frameLinesMatteOutsideAndOutlineInside() {
        val area = GuideLayout.activeArea(GuideSettings(frameLineAspect = 2.39f), full)
        assertEquals(PixelRect(0, 138, 1920, 803), area)
        val r = rects(GuideSettings(frameLineAspect = 2.39f, lineWidthPx = 2))
        assertEquals("top and bottom matte (the side ones are empty) + 4 outline strips", 6, r.size)
        assertEquals(PixelRect(0, 941, 1920, 139), r[0])
        assertEquals(PixelRect(0, 0, 1920, 138), r[1])
        assertEquals(PixelRect(0, 138, 1920, 2), r[2])
    }

    @Test
    fun safeAreaIsAnInsetOutline() {
        val r = rects(GuideSettings(safeAreas = listOf(0.9f), lineWidthPx = 2))
        assertEquals(4, r.size)
        assertEquals(PixelRect(96, 54, 1728, 2), r[0])
        assertEquals(PixelRect(96, 1024, 1728, 2), r[1])
    }

    @Test
    fun centreCrossDoesNotOverlapItself() {
        val r = rects(GuideSettings(centre = true, lineWidthPx = 2))
        assertEquals(3, r.size)
        val covered = mutableSetOf<Pair<Int, Int>>()
        for (rect in r) {
            for (x in rect.x until rect.right) {
                for (y in rect.y until rect.top) assertTrue("($x,$y) drawn twice", covered.add(x to y))
            }
        }
    }

    @Test
    fun guidesFollowTheDesqueezedContent() {
        val content = MonitorGeometry.contentRect(1920, 1080, 1920, 1080, Desqueeze.X2)
        val r = rects(GuideSettings(thirds = true), content)
        assertTrue(r.all { it.y >= content.y && it.top <= content.top })
    }

    @Test
    fun capacityIsNeverExceeded() {
        val many = GuideSettings(thirds = true, centre = true, safeAreas = List(20) { 0.5f + it * 0.02f }, frameLineAspect = 1f)
        assertEquals(GuideLayout.MAX_RECTS, rects(many).size)
    }
}
