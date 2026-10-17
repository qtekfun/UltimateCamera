package com.qtekfun.ultimatecamera.feature.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraDimens
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraIconButton
import com.qtekfun.ultimatecamera.feature.camera.ui.CameraTheme
import com.qtekfun.ultimatecamera.feature.camera.ui.LockChip
import com.qtekfun.ultimatecamera.feature.camera.ui.ModeSwitch
import com.qtekfun.ultimatecamera.feature.camera.ui.OptionChip
import com.qtekfun.ultimatecamera.feature.camera.ui.QuickChip
import com.qtekfun.ultimatecamera.feature.camera.ui.RecordButton
import com.qtekfun.ultimatecamera.feature.camera.ui.StatusStrip
import com.qtekfun.ultimatecamera.feature.camera.ui.formatTimecode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** D67: TalkBack semantics of the camera controls (labels, roles, state, live regions, 48 dp targets). */
@RunWith(AndroidJUnit4::class)
class CameraControlsA11yTest {
    @get:Rule
    val rule = createComposeRule()

    private fun set(content: @Composable () -> Unit) = rule.setContent { CameraTheme(content) }

    @Test
    fun recButtonIsASwitchThatSpeaksTheTakeTime() {
        var recording by mutableStateOf(false)
        set { RecordButton(recording = recording, onToggle = { recording = !recording }, elapsedMs = 72_400) }

        rule.onNodeWithContentDescription(str(R.string.ui_start_recording))
            .assert(hasRole(Role.Switch))
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.ui_rec_standby)))
            .assertHeightIsAtLeast(CameraDimens.touch)
            .performClick()

        rule.onNodeWithContentDescription(str(R.string.ui_stop_recording))
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.ui_rec_state, "00:01:12")))
    }

    @Test
    fun shutterIsAPlainButton() {
        set { RecordButton(recording = false, onToggle = {}, photo = true) }
        rule.onNodeWithContentDescription(str(R.string.ui_take_photo)).assert(hasRole(Role.Button)).assertHasClickAction()
    }

    @Test
    fun timecodeIsAThrottledPoliteLiveRegion() {
        set { StatusStrip(recording = true, timecode = formatTimecode(72_400, 30), badges = listOf("4K30"), elapsedMs = 72_400) }
        rule.onNode(hasContentDescription(str(R.string.ui_rec_state, "00:01:00")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun timeOfDayTimecodeIsNotALiveRegion() {
        set { StatusStrip(recording = true, timecode = "12:00:00:05") }
        rule.onNode(hasContentDescription(str(R.string.ui_rec_state, "12:00:00:05")))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun criticalHealthAlertIsAssertiveAndWarningPolite() {
        set {
            HealthBanner(
                listOf(
                    HealthAlert(HealthKind.THERMAL, AlertLevel.CRITICAL, 0),
                    HealthAlert(HealthKind.BATTERY, AlertLevel.WARNING, 8)
                )
            )
        }
        rule.onNodeWithText(str(R.string.health_alert_thermal_now))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        rule.onNodeWithText(str(R.string.health_alert_battery, 8))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun quickChipIsOneButtonWithNameAndValue() {
        set { QuickChip("HEVC", onClick = {}, contentDescription = str(R.string.ui_codec), active = true) }
        rule.onNode(hasContentDescription(str(R.string.ui_codec)).and(hasText("HEVC")))
            .assert(hasRole(Role.Button))
            .assertIsSelected()
            .assertHeightIsAtLeast(CameraDimens.touch)
    }

    @Test
    fun iconButtonHasLabelRoleAndTarget() {
        set { CameraIconButton(R.drawable.ic_cam_settings, str(R.string.ui_settings), onClick = {}) }
        rule.onNodeWithContentDescription(str(R.string.ui_settings))
            .assert(hasRole(Role.Button))
            .assertHasClickAction()
            .assertHeightIsAtLeast(CameraDimens.touch)
            .assertWidthIsAtLeast(CameraDimens.touch)
    }

    @Test
    fun lockChipIsASwitchWithLockedState() {
        var locked by mutableStateOf(false)
        set { LockChip("AE-L", locked = locked, onToggle = { locked = !locked }) }
        rule.onNodeWithText("AE-L")
            .assert(hasRole(Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.ui_unlocked)))
            .assertHeightIsAtLeast(CameraDimens.touch)
            .performClick()
        rule.onNodeWithText("AE-L")
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.ui_locked)))
    }

    @Test
    fun modeSwitchSegmentsAreSelectedTabs() {
        set { ModeSwitch(photo = false, onSelect = {}) }
        rule.onNodeWithText(str(R.string.ui_mode_video)).assert(hasRole(Role.Tab)).assertIsSelected().assertHeightIsAtLeast(CameraDimens.touch)
        rule.onNodeWithText(str(R.string.ui_mode_photo)).assert(hasRole(Role.Tab)).assertIsNotSelected()
    }

    @Test
    fun optionChipRoles() {
        set {
            Box {
                OptionChip("Zebras", selected = true, onClick = {})
            }
        }
        rule.onNodeWithText("Zebras").assert(hasRole(Role.RadioButton)).assertIsSelected().assertHeightIsAtLeast(CameraDimens.touch)
    }

    @Test
    fun optionChipAsSwitchAndButton() {
        set {
            androidx.compose.foundation.layout.Column {
                OptionChip("Thumbnails", selected = true, onClick = {}, role = Role.Switch)
                OptionChip("Start", selected = true, onClick = {}, role = Role.Button)
            }
        }
        rule.onNodeWithText("Thumbnails").assert(hasRole(Role.Switch)).assertIsOn()
        rule.onNodeWithText("Start").assert(hasRole(Role.Button)).assertHasClickAction()
    }

    @Test
    fun statusStripAtDoubleFontScaleWrapsInsteadOfOverlapping() {
        val badges = listOf("4K30", "HEVC", "10-BIT", "Rec.709")
        set {
            WithFontScale(2f) {
                StatusStrip(
                    recording = true,
                    timecode = formatTimecode(3_599_000, 30),
                    badges = badges,
                    elapsedMs = 3_599_000,
                    modifier = Modifier.width(320.dp).testTag("strip")
                )
            }
        }
        val strip = rule.onNodeWithTag("strip").bounds
        val parts = (listOf(formatTimecode(3_599_000, 30)) + badges).map { rule.onNodeWithText(it, useUnmergedTree = true) }
        parts.forEach { it.assertTextNotClipped() }
        assertInsideWithoutOverlap(strip, parts.map { it.bounds })
        // Tabular numbers shrink: at 200 % system scale the timecode is laid out at the 150 % cap.
        assertEquals(1.5f, parts.first().textLayout().layoutInput.density.fontScale, 0.001f)
    }

    @Test
    fun longOptionLabelWrapsAtDoubleFontScale() {
        set {
            WithFontScale(2f) {
                Box(Modifier.width(200.dp).testTag("box")) { OptionChip("Remove animations and keep the peaking", selected = false, onClick = {}) }
            }
        }
        rule.onNodeWithText("Remove animations and keep the peaking", useUnmergedTree = true).assertTextNotClipped()
    }
}
