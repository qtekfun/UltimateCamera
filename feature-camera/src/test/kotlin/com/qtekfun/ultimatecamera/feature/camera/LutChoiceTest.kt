package com.qtekfun.ultimatecamera.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LutChoiceTest {
    @Test
    fun tokensRoundTrip() {
        listOf(RecordingLook.None, RecordingLook.UcLog, RecordingLook.Library("a-b")).forEach {
            assertEquals(it, RecordingLook.parse(it.token))
        }
        listOf(MonitorLook.Auto, MonitorLook.None, MonitorLook.UcLogToRec709, MonitorLook.Library("x")).forEach {
            assertEquals(it, MonitorLook.parse(it.token))
        }
        assertNull(RecordingLook.None.token)
        assertNull(MonitorLook.Auto.token)
    }

    @Test
    fun unknownTokensFallBackToTheDefaults() {
        assertEquals(RecordingLook.None, RecordingLook.parse("something-new"))
        assertEquals(RecordingLook.None, RecordingLook.parse("lib:"))
        assertEquals(MonitorLook.Auto, MonitorLook.parse("future"))
        assertEquals(MonitorLook.Auto, MonitorLook.parse("lib:"))
    }

    @Test
    fun ucLogShowsTheViewingLutByDefault() {
        val plan = LookPlan.of(RecordingLook.UcLog, MonitorLook.Auto, tenBit = true)
        assertTrue(plan.ucLog)
        assertNull(plan.record)
        assertEquals(listOf(LutRef.UcLogToRec709), plan.monitor)
    }

    @Test
    fun ucLogWithMonitorNoneShowsTheFlatLog() {
        assertEquals(LookPlan(ucLog = true, record = null, monitor = emptyList()), LookPlan.of(RecordingLook.UcLog, MonitorLook.None, tenBit = true))
    }

    @Test
    fun ucLogIsTenBitOnly() {
        val plan = LookPlan.of(RecordingLook.UcLog, MonitorLook.Auto, tenBit = false)
        assertFalse(plan.ucLog)
        assertEquals(emptyList<LutRef>(), plan.monitor)
    }

    @Test
    fun aBakedLutIsAlsoFirstOnTheMonitor() {
        assertEquals(
            LookPlan(ucLog = false, record = LutRef.Library("r"), monitor = listOf(LutRef.Library("r"))),
            LookPlan.of(RecordingLook.Library("r"), MonitorLook.Auto, tenBit = true)
        )
        assertEquals(
            listOf(LutRef.Library("r"), LutRef.Library("m")),
            LookPlan.of(RecordingLook.Library("r"), MonitorLook.Library("m"), tenBit = false).monitor
        )
    }

    @Test
    fun monitorOnlyLutLeavesTheRecordingAlone() {
        val plan = LookPlan.of(RecordingLook.None, MonitorLook.Library("m"), tenBit = true)
        assertNull(plan.record)
        assertFalse(plan.ucLog)
        assertEquals(listOf(LutRef.Library("m")), plan.monitor)
        assertEquals(emptyList<LutRef>(), LookPlan.of(RecordingLook.None, MonitorLook.Auto, tenBit = true).monitor)
    }
}
