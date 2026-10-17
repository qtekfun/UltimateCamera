package com.qtekfun.ultimatecamera.core.recording

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TakeTimecodeTest {
    private val s = 1_000_000_000L

    /** 2026-10-17 00:00:00 UTC. */
    private val midnight = 1_792_195_200L * s

    @Test
    fun bextCarriesTheLocalTimeOfDayOfTheFirstSample() {
        val t = midnight + (21 * 3_600 + 30 * 60) * s + 250_000_000L // 21:30:00.25 UTC
        val madrid = ZoneId.of("Europe/Madrid") // UTC+2 in October (summer time)
        val bext = TakeTimecode.bext(t, 48_000, "A001_T001.mp4", madrid)
        assertEquals(7_200, TakeTimecode.utcOffsetSeconds(t, madrid))
        assertEquals((23 * 3_600 + 30 * 60) * 48_000L + 12_000, bext.timeReferenceSamples)
        assertEquals("2026-10-17", bext.originationDate)
        assertEquals("23:30:00", bext.originationTime)
        assertEquals("A001_T001", bext.originatorReference)
        // Past local midnight the date moves on and the reference restarts.
        val late = TakeTimecode.bext(t + 3_600 * s, 48_000, "x", madrid)
        assertEquals("2026-10-18", late.originationDate)
        assertEquals((30 * 60) * 48_000L + 12_000, late.timeReferenceSamples)
    }
}
