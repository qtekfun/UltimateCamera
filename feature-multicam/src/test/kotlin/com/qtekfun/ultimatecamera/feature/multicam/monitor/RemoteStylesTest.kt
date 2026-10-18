package com.qtekfun.ultimatecamera.feature.multicam.monitor

import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import com.qtekfun.ultimatecamera.core.net.control.CameraStatus
import com.qtekfun.ultimatecamera.core.net.control.ChoiceInfo
import com.qtekfun.ultimatecamera.core.net.control.CommandBody
import com.qtekfun.ultimatecamera.core.net.control.ControlMessage
import com.qtekfun.ultimatecamera.core.net.control.ControlProtocol
import com.qtekfun.ultimatecamera.core.net.control.Decoded
import com.qtekfun.ultimatecamera.core.net.role.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStylesTest {
    private val vibrant = StyleParams.of(StylePreset.VIBRANT)
    private val tuned = StyleParams(StylePreset.VIBRANT, tone = 0.4f, warmth = -0.3f, saturation = 0.2f, intensity = 0.7f)
    private val offered = listOf(
        ChoiceInfo(vibrant.token, "Vívido"),
        ChoiceInfo(StyleParams.of(StylePreset.MONO).token, "Mono"),
        ChoiceInfo("style:future_look:0.00,0.00,0.00,1.00", "Future look")
    )

    private fun ui(lutId: String? = null, monitorLook: String? = null, styles: List<ChoiceInfo> = offered): RemoteCameraUi = RemoteCameraMapper.map(
        RemoteSnapshot(
            "cam",
            "Cam A",
            LinkState.CONNECTED,
            CameraStatus("boot", 1, lutId = lutId, monitorLook = monitorLook, styles = styles),
            everConnected = true
        )
    )

    @Test
    fun theStatusCarriesTheCamerasStylesAndBothLooks() {
        val u = ui(lutId = tuned.token, monitorLook = "style:mono:0.00,0.00,0.00,0.50")
        assertEquals(offered, u.styles)
        assertEquals(tuned, RemoteStyles.current(u, StyleTarget.RECORDING))
        assertEquals(StyleParams(StylePreset.MONO, intensity = 0.5f), RemoteStyles.current(u, StyleTarget.MONITOR))
        // A LUT or no look is not a style.
        assertNull(RemoteStyles.current(ui(lutId = "lib:abc"), StyleTarget.RECORDING))
        assertNull(RemoteStyles.current(ui(), StyleTarget.MONITOR))
    }

    @Test
    fun knownPresetsAreTunableUnknownOnesOnlyPickable() {
        val options = RemoteStyles.options(offered)
        assertEquals(listOf(StylePreset.VIBRANT, StylePreset.MONO, null), options.map { it.preset })
        assertEquals("Future look", options.last().label)
    }

    @Test
    fun eachLookGoesThroughItsOwnCommandAndTheCameraReadsItBack() {
        val recording = RemoteStyles.command(StyleTarget.RECORDING, tuned)
        val monitor = RemoteStyles.command(StyleTarget.MONITOR, tuned)
        assertEquals(CommandBody.SelectLut(tuned.token), recording)
        assertEquals(CommandBody.SetMonitorLook(tuned.token), monitor)
        assertEquals(CommandBody.SelectLut(null), RemoteStyles.command(StyleTarget.RECORDING, null as String?))
        assertEquals(CommandBody.SetMonitorLook(null), RemoteStyles.command(StyleTarget.MONITOR, null as String?))
        // On the wire and back, the token is exactly what the camera parses into the same style.
        for (body in listOf(recording, monitor)) {
            val decoded = ControlProtocol.decode(ControlProtocol.encode(ControlMessage.Command("c", body))) as Decoded.Message
            val sent = (decoded.message as ControlMessage.Command).body
            val token = (sent as? CommandBody.SelectLut)?.lutId ?: (sent as CommandBody.SetMonitorLook).token
            assertEquals(tuned, StyleParams.parse(token))
        }
    }

    @Test
    fun aPresetIsSelectedWhateverItWasTunedTo() {
        val options = RemoteStyles.options(offered)
        assertTrue(RemoteStyles.selected(tuned, tuned.token, options[0]))
        assertFalse(RemoteStyles.selected(tuned, tuned.token, options[1]))
        assertFalse(RemoteStyles.selected(null, null, options[0]))
        // An unknown preset matches by its token only.
        assertTrue(RemoteStyles.selected(null, options[2].token, options[2]))
    }

    @Test
    fun aRecordingStyleDuringATakeIsForTheNextTakeOnly() {
        assertTrue(RemoteStyles.appliesNextTake(StyleTarget.RECORDING, recording = true))
        assertFalse(RemoteStyles.appliesNextTake(StyleTarget.RECORDING, recording = false))
        assertFalse(RemoteStyles.appliesNextTake(StyleTarget.MONITOR, recording = true))
    }

    @Test
    fun theLookChipNamesLutsAndStyles() {
        val luts = listOf(ChoiceInfo("lib:abc", "Teal"))
        val name: (StylePreset) -> String = { "name-${it.id}" }
        assertEquals("Teal", RemoteStyles.lookLabel("lib:abc", luts, name))
        assertEquals("name-vibrant", RemoteStyles.lookLabel(tuned.token, luts, name))
        assertEquals("uclog", RemoteStyles.lookLabel("uclog", luts, name))
        assertNull(RemoteStyles.lookLabel(null, luts, name))
    }

    @Test
    fun theDraftIsKeptUntilTheCameraConfirmsIt() {
        assertEquals(tuned, RemoteStyles.settle(tuned, vibrant))
        assertNull(RemoteStyles.settle(tuned, tuned))
        assertNull(RemoteStyles.settle(null, vibrant))
    }

    @Test
    fun thePanelOffersNonePlusTheCamerasPresetsWithTheirCommands() {
        val recording = RemoteStyles.panel(ui(lutId = tuned.token), StyleTarget.RECORDING, draft = null)
        assertEquals(4, recording.chips.size)
        assertTrue(recording.chips[0].none)
        assertFalse(recording.chips[0].selected)
        assertEquals(CommandBody.SelectLut(null), recording.chips[0].command)
        assertTrue("the tuned Vibrant is the Vibrant chip", recording.chips[1].selected)
        assertEquals(CommandBody.SelectLut(StyleParams.of(StylePreset.MONO).token), recording.chips[2].command)
        assertEquals("Future look", recording.chips[3].cameraLabel)
        assertEquals(tuned, recording.shown)
        assertFalse(recording.nextTake)
        assertFalse(recording.notTunable)

        // Monitor look automatic: "Auto" selected, no tuner; its chips send set_monitor_look.
        val monitor = RemoteStyles.panel(ui(lutId = tuned.token), StyleTarget.MONITOR, draft = null)
        assertTrue(monitor.chips[0].selected)
        assertNull(monitor.shown)
        assertEquals(CommandBody.SetMonitorLook(vibrant.token), monitor.chips[1].command)
        // The user's draft is in the tuner until the camera confirms it.
        assertEquals(vibrant, RemoteStyles.panel(ui(), StyleTarget.MONITOR, draft = vibrant).shown)
    }

    @Test
    fun aPresetOnlyTheCameraKnowsCanBePickedNotTuned() {
        val panel = RemoteStyles.panel(ui(lutId = offered[2].id), StyleTarget.RECORDING, draft = null)
        assertTrue(panel.chips[3].selected)
        assertNull(panel.shown)
        assertTrue(panel.notTunable)
        // A LUT is neither a style nor "not tunable".
        val lut = RemoteStyles.panel(ui(lutId = "lib:abc"), StyleTarget.RECORDING, draft = null)
        assertFalse(lut.notTunable)
        assertTrue(lut.chips.none { it.selected })
    }

    @Test
    fun anOlderCameraWithoutStylesOffersNone() {
        assertTrue(ui(styles = emptyList()).styles.isEmpty())
    }
}
