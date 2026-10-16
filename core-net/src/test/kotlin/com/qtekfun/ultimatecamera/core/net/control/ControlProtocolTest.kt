package com.qtekfun.ultimatecamera.core.net.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlProtocolTest {
    private val status = CameraStatus(
        bootId = "boot",
        version = 7,
        recording = RecordingState.RECORDING,
        recordingId = "take-1",
        recordingStartCommonNanos = 1_790_000_000_123_456_789L,
        clockSynced = true,
        clockErrorMicros = 850,
        batteryPercent = 64,
        thermal = ThermalLevel.MODERATE,
        freeSpaceMinutes = 95,
        profileId = "4k30-hlg",
        lutId = "rec709",
        audioSource = "usb",
        capture = CaptureSettings(autoExposure = false, iso = 400, exposureTimeNanos = 20_000_000),
        warnings = setOf(StatusWarning.STORAGE_LOW)
    )

    private val messages: List<ControlMessage> = listOf(
        ControlMessage.Heartbeat(1, 2, 3),
        ControlMessage.Heartbeat(5),
        ControlMessage.Status(status),
        ControlMessage.Command("c1", CommandBody.StartRecording("take-1", 1_790_000_000_500_000_000L)),
        ControlMessage.Command("c2", CommandBody.StopRecording()),
        ControlMessage.Command("c3", CommandBody.SetExposure(auto = false, iso = 800, exposureTimeNanos = 10_000_000)),
        ControlMessage.Command("c4", CommandBody.SetFocus(auto = false, distanceDiopters = 0.5f)),
        ControlMessage.Command("c5", CommandBody.SetWhiteBalance(auto = false, kelvin = 5_600)),
        ControlMessage.Command("c6", CommandBody.SelectProfile("1080p60")),
        ControlMessage.Command("c7", CommandBody.SelectLut(null)),
        ControlMessage.Command("c8", CommandBody.RequestStatus),
        ControlMessage.Ack("c1", detail = AckDetail(atCommonNanos = 9, lateMicros = 1_200)),
        ControlMessage.Ack("c2", duplicate = true),
        ControlMessage.Error("c3", ErrorCode.INVALID_ARGUMENT, "iso"),
        ControlMessage.Error(null, ErrorCode.UNKNOWN_MESSAGE),
        ControlMessage.RecordingFinished(ClipInfo("take-1", "UC_0001.mp4", 1L, 61_000, 812_000_000, "10:00:00:00")),
        ControlMessage.Bye(ByeReason.UNPAIRED)
    )

    @Test
    fun everyMessageRoundTrips() {
        for (message in messages) {
            assertEquals(Decoded.Message(message), ControlProtocol.decode(ControlProtocol.encode(message)))
        }
    }

    @Test
    fun wireFormatIsTypedCompactJson() {
        assertEquals(
            """{"type":"command","id":"c1","body":{"type":"start_recording","recordingId":"t"}}""",
            ControlProtocol.encode(ControlMessage.Command("c1", CommandBody.StartRecording("t"))).toString(Charsets.UTF_8)
        )
        assertEquals("""{"type":"ack","commandId":"x"}""", ControlProtocol.encode(ControlMessage.Ack("x")).toString(Charsets.UTF_8))
    }

    @Test
    fun unknownTypesAndFieldsFromANewerPeerAreTolerated() {
        assertEquals(Decoded.Unknown("set_zoom"), ControlProtocol.decode("""{"type":"set_zoom","ratio":2}""".toByteArray()))
        assertEquals(
            Decoded.Message(ControlMessage.Ack("x")),
            ControlProtocol.decode("""{"type":"ack","commandId":"x","newField":[1,2]}""".toByteArray())
        )
    }

    @Test
    fun malformedMessagesThrow() {
        for (text in listOf("not json", "[]", """{"no":"type"}""", """{"type":"ack"}""", """{"type":"command","id":"c","body":{"type":"warp"}}""")) {
            val thrown = runCatching { ControlProtocol.decode(text.toByteArray()) }.exceptionOrNull()
            assertTrue("$text -> $thrown", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun statusOrderingByBootAndVersion() {
        val older = status.copy(version = 3)
        assertTrue(older.isSupersededBy(status))
        assertTrue(!status.isSupersededBy(older))
        assertTrue(!status.isSupersededBy(status))
        assertTrue(status.isSupersededBy(older.copy(bootId = "rebooted")))
    }
}
