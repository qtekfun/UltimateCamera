package com.qtekfun.ultimatecamera.core.gpu.meters

import kotlin.math.roundToInt

/** An integer pixel rectangle in GL window order: ([x], [y]) is the bottom-left pixel. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val top: Int get() = y + height
}

/** Where the (desqueezed) image lands inside a viewport. Shared by the GPU renderer and the CPU reference. */
object MonitorGeometry {
    /**
     * The largest centred rectangle of the desqueezed source aspect (`sourceWidth × factor : sourceHeight`) that fits
     * the [viewWidth]×[viewHeight] viewport; the rest is letterboxed in black. Relative to the viewport origin.
     */
    fun contentRect(viewWidth: Int, viewHeight: Int, sourceWidth: Int, sourceHeight: Int, desqueeze: Desqueeze): PixelRect {
        require(viewWidth > 0 && viewHeight > 0 && sourceWidth > 0 && sourceHeight > 0) { "empty viewport or source" }
        val aspect = sourceWidth * desqueeze.factor / sourceHeight
        return fit(PixelRect(0, 0, viewWidth, viewHeight), aspect)
    }

    /** The largest centred rectangle of [aspect] (width / height) inside [area]. */
    fun fit(area: PixelRect, aspect: Float): PixelRect {
        val areaAspect = area.width.toFloat() / area.height
        val (w, h) = if (aspect >= areaAspect) {
            area.width to (area.width / aspect).roundToInt().coerceIn(1, area.height)
        } else {
            (area.height * aspect).roundToInt().coerceIn(1, area.width) to area.height
        }
        return PixelRect(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h)
    }
}

/**
 * Preallocated list of guide rectangles (pixels, viewport-relative, GL order) and their ARGB colours. Filled by
 * [GuideLayout.layout] when the settings or the viewport change; the renderer uploads it as one uniform array.
 */
class GuideRects(val capacity: Int = GuideLayout.MAX_RECTS) {
    /** x, y, width, height per rectangle. */
    val rects = IntArray(capacity * 4)
    val colors = IntArray(capacity)
    var count = 0
        private set

    fun clear() {
        count = 0
    }

    internal fun add(x: Int, y: Int, width: Int, height: Int, color: Int) {
        if (width <= 0 || height <= 0 || count == capacity) return
        rects[count * 4] = x
        rects[count * 4 + 1] = y
        rects[count * 4 + 2] = width
        rects[count * 4 + 3] = height
        colors[count] = color
        count++
    }
}

/**
 * Turns [GuideSettings] into non-overlapping rectangles (except where two thirds lines cross) drawn in this order with
 * source-over blending: matte, frame lines, safe areas, thirds, centre cross. Lines are [GuideSettings.lineWidthPx]
 * wide and lie inside the area they outline.
 */
object GuideLayout {
    const val MAX_RECTS = 64

    /** The area the guides refer to: the frame-line rectangle inside [content], or [content] itself. */
    fun activeArea(settings: GuideSettings, content: PixelRect): PixelRect = settings.frameLineAspect?.let { MonitorGeometry.fit(content, it) } ?: content

    fun layout(settings: GuideSettings, content: PixelRect, out: GuideRects) {
        out.clear()
        val lw = settings.lineWidthPx
        val color = settings.lineColor
        val area = activeArea(settings, content)
        if (settings.frameLineAspect != null) {
            val matte = (settings.matteOpacity * 255f).roundToInt() shl 24
            out.add(content.x, area.top, content.width, content.top - area.top, matte)
            out.add(content.x, content.y, content.width, area.y - content.y, matte)
            out.add(content.x, area.y, area.x - content.x, area.height, matte)
            out.add(area.right, area.y, content.right - area.right, area.height, matte)
            outline(area, lw, color, out)
        }
        for (i in settings.safeAreas.indices) {
            val f = settings.safeAreas[i]
            val w = (area.width * f).roundToInt()
            val h = (area.height * f).roundToInt()
            outline(PixelRect(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h), lw, color, out)
        }
        if (settings.thirds) {
            for (k in 1..2) {
                out.add(area.x + area.width * k / 3 - lw / 2, area.y, lw, area.height, color)
                out.add(area.x, area.y + area.height * k / 3 - lw / 2, area.width, lw, color)
            }
        }
        if (settings.centre) {
            val arm = maxOf(lw * 4, minOf(area.width, area.height) / 12)
            val cx = area.x + area.width / 2
            val cy = area.y + area.height / 2
            val barY = cy - lw / 2
            out.add(cx - arm, barY, 2 * arm, lw, color)
            out.add(cx - lw / 2, cy - arm, lw, barY - (cy - arm), color)
            out.add(cx - lw / 2, barY + lw, lw, cy + arm - barY - lw, color)
        }
    }

    private fun outline(r: PixelRect, lw: Int, color: Int, out: GuideRects) {
        if (r.width <= 2 * lw || r.height <= 2 * lw) {
            out.add(r.x, r.y, r.width, r.height, color)
            return
        }
        out.add(r.x, r.y, r.width, lw, color)
        out.add(r.x, r.top - lw, r.width, lw, color)
        out.add(r.x, r.y + lw, lw, r.height - 2 * lw, color)
        out.add(r.right - lw, r.y + lw, lw, r.height - 2 * lw, color)
    }
}
