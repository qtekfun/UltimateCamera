package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.core.capabilities.ProbeOutcome
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraDimens
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** D67: auto-configuration screen semantics and its 200 % font-scale layout on a small phone. */
@RunWith(AndroidJUnit4::class)
class AutoConfigScreenA11yTest {
    @get:Rule
    val rule = createComposeRule()

    private val done = AutoConfigUiState(
        phase = AutoConfigPhase.DONE,
        done = 2,
        total = 2,
        rows = listOf(
            ProbeRow("2160p60 HEVC 10-bit", ProbeOutcome.DROPS, measuredFps = 52.1, droppedFrames = 40),
            ProbeRow("2160p30 HEVC 10-bit", ProbeOutcome.ACCEPTED_AND_HELD, measuredFps = 30.0, droppedFrames = 0)
        ),
        elapsedMs = 42_000
    )

    @Test
    fun actionsAreButtonsAndTheTitleIsAHeading() {
        var started = 0
        var finished = 0
        rule.setContent { CameraTheme { AutoConfigContent(AutoConfigUiState(), start = { started++ }, cancel = {}, finish = { finished++ }) } }

        rule.onNodeWithText(str(R.string.autoconfig_title).uppercase())
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText(str(R.string.autoconfig_start))
            .assert(hasRole(Role.Button))
            .assertHeightIsAtLeast(CameraDimens.touch)
            .performClick()
        rule.onNodeWithText(str(R.string.autoconfig_skip)).assert(hasRole(Role.Button)).performClick()
        rule.onNodeWithContentDescription(str(R.string.ui_close)).assert(hasRole(Role.Button)).assertHasClickAction()
        assertEquals(1, started)
        assertEquals(1, finished)
    }

    @Test
    fun theScreenStaysOnWhileARunIsInProgress() {
        // D108: the screen timeout stopped the run.
        var view: android.view.View? = null
        var state by mutableStateOf(AutoConfigUiState(phase = AutoConfigPhase.RUNNING))
        rule.setContent {
            view = LocalView.current
            CameraTheme { AutoConfigContent(state, start = {}, cancel = {}, finish = {}) }
        }
        rule.runOnIdle { assertTrue(view!!.keepScreenOn) }
        state = done
        rule.runOnIdle { assertFalse(view!!.keepScreenOn) }
    }

    @Test
    fun eachProbeIsOneStopWithModeAndVerdict() {
        rule.setContent { CameraTheme { AutoConfigContent(done, start = {}, cancel = {}, finish = {}) } }
        // The merged row carries the mode and its verdict, so TalkBack reads them together.
        rule.onNodeWithText("2160p30 HEVC 10-bit").assert(
            SemanticsMatcher("has the verdict") { node ->
                (
                    if (SemanticsProperties.Text in
                        node.config
                    ) {
                        node.config[SemanticsProperties.Text]
                    } else {
                        emptyList()
                    }
                    ).any { it.text == str(R.string.autoconfig_outcome_held) }
            }
        )
    }

    @Test
    fun doubleFontScaleOnASmallPhoneWrapsWithoutClippingOrOverlap() {
        rule.setContent {
            WithFontScale(2f) {
                CameraTheme {
                    Box(Modifier.size(360.dp, 740.dp).testTag("screen")) { AutoConfigContent(done, start = {}, cancel = {}, finish = {}) }
                }
            }
        }
        val screen = rule.onNodeWithTag("screen").bounds
        val again = rule.onNodeWithText(str(R.string.autoconfig_again), useUnmergedTree = true).assertTextNotClipped()
        val apply = rule.onNodeWithText(str(R.string.autoconfig_apply), useUnmergedTree = true).assertTextNotClipped()
        rule.onNodeWithText(str(R.string.autoconfig_body), useUnmergedTree = true).assertTextNotClipped()
        rule.onNodeWithText(str(R.string.autoconfig_title).uppercase(), useUnmergedTree = true).assertTextNotClipped()
        assertInsideWithoutOverlap(screen, listOf(again.bounds, apply.bounds))
    }
}
