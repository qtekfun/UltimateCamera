package com.qtekfun.ultimatecamera.core.net.control

import com.qtekfun.ultimatecamera.core.net.R
import com.qtekfun.ultimatecamera.core.net.pairing.PairingError
import com.qtekfun.ultimatecamera.core.net.role.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every state and error the multicamera UI can show has its own message; none share one or fall back to another. */
class ControlStringsTest {
    private fun assertDistinct(ids: List<Int>, what: String) {
        assertEquals("$what: one message each", ids.size, ids.toSet().size)
    }

    @Test
    fun everyPairingErrorHasItsOwnMessage() {
        assertDistinct(PairingError.entries.map(ControlStrings::of), "pairing errors")
        assertEquals(R.string.net_pairing_error_expired, ControlStrings.of(PairingError.EXPIRED))
    }

    @Test
    fun everyRejectionHasItsOwnMessage() {
        assertDistinct(RejectCode.entries.map(ControlStrings::of), "rejections")
        assertEquals(R.string.net_reject_bad_proof, ControlStrings.of(RejectCode.BAD_PROOF))
    }

    @Test
    fun everyLinkStateHasItsOwnMessage() {
        assertDistinct(LinkState.entries.map(ControlStrings::of), "link states")
        assertEquals(R.string.net_link_connected, ControlStrings.of(LinkState.CONNECTED))
    }

    @Test
    fun everyWarningHasItsOwnMessage() {
        assertDistinct(StatusWarning.entries.map(ControlStrings::of), "warnings")
        assertEquals(R.string.net_warning_clock, ControlStrings.of(StatusWarning.CLOCK_NOT_SYNCED))
    }

    @Test
    fun everyErrorCodeHasItsOwnMessage() {
        assertDistinct(ErrorCode.entries.map(ControlStrings::of), "error codes")
        assertEquals(R.string.net_error_clock_not_synced, ControlStrings.of(ErrorCode.CLOCK_NOT_SYNCED))
        assertEquals(R.string.net_error_timed_out, ControlStrings.of(ErrorCode.TIMED_OUT))
    }
}
