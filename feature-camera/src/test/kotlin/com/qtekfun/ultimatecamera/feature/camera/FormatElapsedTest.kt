package com.qtekfun.ultimatecamera.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatElapsedTest {
    @Test
    fun minutesAndSecondsBeforeAnHour() {
        assertEquals("00:00", formatElapsed(999))
        assertEquals("01:05", formatElapsed(65_000))
        assertEquals("59:59", formatElapsed(3_599_999))
    }

    @Test
    fun hoursFromOneHourOn() {
        assertEquals("1:00:00", formatElapsed(3_600_000))
        assertEquals("2:03:04", formatElapsed(7_384_000))
    }
}
