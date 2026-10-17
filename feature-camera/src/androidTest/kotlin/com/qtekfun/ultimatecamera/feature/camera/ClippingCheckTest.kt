package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The clipping check used by the 200 % font-scale tests ([isClipped]) must catch real clipping and must not flag a
 * short text that simply does not fill the width it was offered (the false positive of `hasVisualOverflow` read from
 * `GetTextLayoutResult`, which failed "USB", "Repetir" and the timecode on the Pixel 8).
 */
@RunWith(AndroidJUnit4::class)
class ClippingCheckTest {
    @get:Rule
    val rule = createComposeRule()

    private fun clipped(text: String) = isClipped(rule.onNodeWithText(text, useUnmergedTree = true).textLayout())

    @Test
    fun shortTextInAWideSpaceIsNotClipped() {
        rule.setContent { WithFontScale(2f) { Box(Modifier.width(360.dp)) { Text("USB", fontSize = 14.sp) } } }
        assertFalse(clipped("USB"))
    }

    @Test
    fun wrappingTextIsNotClipped() {
        val long = "Where takes are written; a USB drive or SD card is tested before it is offered."
        rule.setContent { WithFontScale(2f) { Box(Modifier.width(200.dp)) { Text(long, fontSize = 14.sp) } } }
        assertFalse(clipped(long))
    }

    @Test
    fun oneLineTextCutByMaxLinesIsClipped() {
        rule.setContent { Box(Modifier.width(80.dp)) { Text("Use the results of the run", fontSize = 14.sp, maxLines = 1) } }
        assertTrue(clipped("Use the results of the run"))
    }

    @Test
    fun unwrappedTextWiderThanItsSpaceIsClipped() {
        rule.setContent { Box(Modifier.width(60.dp)) { Text("00:59:59:00", fontSize = 16.sp, softWrap = false, maxLines = 1) } }
        assertTrue(clipped("00:59:59:00"))
    }

    @Test
    fun textTallerThanAFixedHeightIsClipped() {
        val long = "A label that needs several lines at this width"
        rule.setContent { Box(Modifier.size(80.dp, 20.dp)) { Text(long, fontSize = 14.sp) } }
        assertTrue(clipped(long))
    }
}
