package com.qtekfun.ultimatecamera.feature.camera.ui

import androidx.compose.ui.graphics.Color
import com.qtekfun.ultimatecamera.feature.camera.ALERT_RED
import com.qtekfun.ultimatecamera.feature.camera.HOT_ORANGE
import com.qtekfun.ultimatecamera.feature.camera.OK_WHITE
import com.qtekfun.ultimatecamera.feature.camera.WARN_YELLOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D67, REQ-NFR accessibility: every text colour of the camera design system reaches WCAG AA (4.5:1) over the scrims,
 * whatever is behind them. The worst case for light text is a white sky, for dark text a black frame; both are tested.
 */
class ContrastTest {
    private val colors = CameraColors(accent = Color.Magenta, onAccent = Color.Black)
    private val images = listOf("white sky" to Color.White, "black frame" to Color.Black)
    private val scrims = listOf("scrim" to colors.scrim, "scrimStrong" to colors.scrimStrong)

    private val textTokens = listOf(
        "content" to colors.content,
        "contentMuted" to colors.contentMuted,
        "warning" to colors.warning,
        "okText" to colors.okText,
        "alertText" to colors.alertText,
        "status OK_WHITE" to OK_WHITE,
        "status WARN_YELLOW" to WARN_YELLOW,
        "status HOT_ORANGE" to HOT_ORANGE,
        "status ALERT_RED" to ALERT_RED
    )

    @Test
    fun `reference values match WCAG`() {
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 0.01)
        assertEquals(1.0, contrastRatio(Color.Red, Color.Red), 0.0001)
        // #767676 on white is the classic 4.54:1 AA limit.
        assertEquals(4.54, contrastRatio(Color(0xFF767676), Color.White), 0.01)
    }

    @Test
    fun `compositing a translucent scrim follows source-over`() {
        val grey = over(Color(0x80000000), Color.White)
        assertEquals(0.498f, grey.red, 0.01f)
        assertEquals(1f, grey.alpha, 0f)
    }

    @Test
    fun `every text token reaches 4_5 to 1 over both scrims on any image`() {
        val failures = buildList {
            for ((scrimName, scrim) in scrims) {
                for ((imageName, image) in images) {
                    for ((textName, text) in textTokens) {
                        val ratio = contrastOverImage(text, scrim, image)
                        if (ratio < MIN_TEXT_CONTRAST) add("$textName on $scrimName over $imageName: %.2f".format(ratio))
                    }
                }
            }
        }
        assertTrue("Below 4.5:1: $failures", failures.isEmpty())
    }

    @Test
    fun `health banner lines reach 4_5 to 1 on any image`() {
        for ((_, image) in images) {
            assertTrue(contrastOverImage(Color.Black, colors.bannerWarning, image) >= MIN_TEXT_CONTRAST)
            assertTrue(contrastOverImage(Color.White, colors.bannerCritical, image) >= MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun `the old tokens are what this guards against`() {
        // 0x99 scrim with 0xB3 muted text was 3.8:1 over a white sky; the red REC colour as text 1.6:1.
        assertTrue(contrastOverImage(Color(0xB3FFFFFF), Color(0x99000000), Color.White) < MIN_TEXT_CONTRAST)
        assertTrue(contrastOverImage(colors.rec, colors.scrim, Color.White) < MIN_TEXT_CONTRAST)
    }
}
