package com.qtekfun.ultimatecamera.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** D67: TalkBack semantics and 200 % font-scale layout of the settings rows and the Help entry. */
@RunWith(AndroidJUnit4::class)
class SettingsA11yTest {
    @get:Rule
    val rule = createComposeRule()

    private fun str(@StringRes id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun set(fontScale: Float = 1f, content: @Composable () -> Unit) = rule.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
        }
    }

    private fun SemanticsNodeInteraction.assertTextNotClipped() {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertTrue(results.isNotEmpty())
        assertFalse("clipped: ${results.first().layoutInput.text}", results.first().hasVisualOverflow)
    }

    @Test
    fun switchRowIsOneSwitchWithItsState() {
        var on by mutableStateOf(false)
        set { SwitchRow("Max brightness", "Full brightness while recording", on, { on = it }) }
        rule.onNodeWithText("Max brightness").assert(hasRole(Role.Switch)).assertIsOff().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Max brightness").assertIsOn()
    }

    @Test
    fun settingsSwitchItemTogglesAsAWholeRow() {
        var on by mutableStateOf(true)
        set { SettingsSwitchItem("Volume keys record", "Either volume key starts and stops", on, { on = it }) }
        rule.onNodeWithText("Volume keys record").assertIsOn().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Volume keys record").assertIsOff()
    }

    @Test
    fun radioRowsAreRadioButtonsWithATarget() {
        set {
            Column(Modifier.selectableGroup()) {
                RadioRow("Record run", selected = true, onSelect = {})
                RadioRow("Time of day", selected = false, onSelect = {})
            }
        }
        rule.onNodeWithText("Record run").assert(hasRole(Role.RadioButton)).assertIsSelected().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Time of day").assert(hasRole(Role.RadioButton)).assertIsNotSelected().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun helpEntryIsAClickableRowUnderAHeading() {
        set { HelpSection() }
        rule.onNodeWithText(str(R.string.settings_section_help)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText(str(R.string.settings_help_open)).assertHasClickAction().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun navItemAtDoubleFontScaleWrapsWithoutOverlap() {
        val title = "Recording location and write speed test"
        val description = "Where takes are written; a USB drive or SD card is tested before it is offered."
        set(fontScale = 2f) {
            Column(Modifier.width(360.dp).testTag("list")) {
                SettingsNavItem(title = title, description = description, onClick = {}, value = "USB")
            }
        }
        val parts = listOf(title, description, "USB").map { rule.onNodeWithText(it, useUnmergedTree = true) }
        parts.forEach { it.assertTextNotClipped() }
        val list = rule.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
        val rects = parts.map { it.fetchSemanticsNode().boundsInRoot }
        rects.forEach { assertTrue("$it outside $list", it.left >= list.left - 0.5f && it.right <= list.right + 0.5f) }
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                assertFalse(
                    "${rects[i]} overlaps ${rects[j]}",
                    rects[i].deflate(0.5f).overlaps(rects[j].deflate(0.5f))
                )
            }
        }
    }
}
