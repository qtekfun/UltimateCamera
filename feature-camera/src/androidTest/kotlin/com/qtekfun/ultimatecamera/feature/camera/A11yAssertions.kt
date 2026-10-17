package com.qtekfun.ultimatecamera.feature.camera

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** A string of the module in the phone's language, so the tests pass in English and Spanish. */
fun str(@StringRes id: Int, vararg args: Any): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

fun hasRole(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

/** [content] with the system font scale set to [scale] (200 % is the Android maximum). */
@Composable
fun WithFontScale(scale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
}

/** The text of this node is not clipped: no line was cut by maxLines or by the size it was laid out at. */
fun SemanticsNodeInteraction.assertTextNotClipped(): SemanticsNodeInteraction {
    val layout = textLayout()
    assertFalse("text is clipped: ${layout.layoutInput.text}", isClipped(layout))
    return this
}

/**
 * Whether [layout]'s text does not fit the size the node really has. The result of `GetTextLayoutResult` cannot be
 * read directly: Compose (foundation 1.12, `ParagraphLayoutCache.slowCreateTextLayoutResultOrNull`) lays the text out
 * again at the full incoming max width but keeps the node's real size, so `hasVisualOverflow` is true for any text
 * narrower than the space it was offered ("USB", "Repetir"). The text is measured again here at the node's real size.
 */
fun isClipped(layout: TextLayoutResult): Boolean {
    val input = layout.layoutInput
    val measured = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection).measure(
        text = input.text,
        style = input.style,
        overflow = input.overflow,
        softWrap = input.softWrap,
        maxLines = input.maxLines,
        placeholders = input.placeholders,
        constraints = Constraints(maxWidth = layout.size.width, maxHeight = layout.size.height),
        layoutDirection = input.layoutDirection,
        density = input.density,
        fontFamilyResolver = input.fontFamilyResolver
    )
    return measured.hasVisualOverflow
}

/** The text layout of a Text node (unmerged tree). */
fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    assertTrue("no text layout", results.isNotEmpty())
    return results.first()
}

val SemanticsNodeInteraction.bounds: Rect get() = fetchSemanticsNode().boundsInRoot

/** Every rect lies inside [container] (half a pixel of rounding allowed) and no two of them overlap. */
fun assertInsideWithoutOverlap(container: Rect, rects: List<Rect>) {
    val slack = 0.5f
    rects.forEach { r ->
        assertTrue("$r outside $container", r.left >= container.left - slack && r.right <= container.right + slack)
        assertTrue("$r outside $container", r.top >= container.top - slack && r.bottom <= container.bottom + slack)
    }
    for (i in rects.indices) {
        for (j in i + 1 until rects.size) {
            val a = rects[i].deflate(slack)
            val b = rects[j].deflate(slack)
            assertFalse("${rects[i]} overlaps ${rects[j]}", a.overlaps(b))
        }
    }
}
