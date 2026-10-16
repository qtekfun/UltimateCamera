package com.qtekfun.ultimatecamera.core.audio

import com.qtekfun.ultimatecamera.core.audio.AudioSourceNotice.NewDeviceDuringTake
import com.qtekfun.ultimatecamera.core.audio.AudioSourceNotice.RoutedElsewhere
import com.qtekfun.ultimatecamera.core.audio.AudioSourceNotice.SelectionReplaced
import com.qtekfun.ultimatecamera.core.audio.AudioSourceNotice.SourceLostDuringTake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourceSelectionTest {
    private val builtin = AudioSource(1, AudioInputKind.BUILTIN, "mic")
    private val bt = AudioSource(2, AudioInputKind.BLUETOOTH, "buds")
    private val usb = AudioSource(3, AudioInputKind.USB, "usb")

    @Test
    fun preselectsTheBestDeviceAndUpgradesAnAutomaticChoice() {
        var s = AudioSourceSelection().onDevicesChanged(listOf(builtin))
        assertEquals(builtin, s.selected)
        s = s.onDevicesChanged(listOf(builtin, bt))
        assertEquals(bt, s.selected)
        s = s.onDevicesChanged(listOf(builtin, bt, usb))
        assertEquals(usb, s.selected)
        assertEquals(listOf(usb, bt, builtin), s.available)
        assertTrue(s.notices.isEmpty())
    }

    @Test
    fun aManualChoiceIsKeptWhileItsDeviceExists() {
        var s = AudioSourceSelection().onDevicesChanged(listOf(builtin, bt)).select(builtin.id)
        s = s.onDevicesChanged(listOf(builtin, bt, usb))
        assertEquals(builtin, s.selected)
        assertTrue(s.chosenByUser)
        s = s.onDevicesChanged(listOf(bt, usb))
        assertEquals(usb, s.selected)
        assertFalse(s.chosenByUser)
        assertEquals(listOf<AudioSourceNotice>(SelectionReplaced(builtin, usb)), s.notices)
    }

    @Test
    fun duringATakeTheSourceIsKeptAndChangesOnlyWarn() {
        var s = AudioSourceSelection().onDevicesChanged(listOf(builtin, bt)).onRecordingStarted()
        assertEquals(bt, s.selected)
        s = s.onDevicesChanged(listOf(builtin, bt, usb))
        assertEquals("never switches mid-take", bt, s.selected)
        assertEquals(listOf<AudioSourceNotice>(NewDeviceDuringTake(usb)), s.notices)
        s = s.onDevicesChanged(listOf(builtin, usb))
        assertEquals(bt, s.selected)
        assertEquals(SourceLostDuringTake(bt), s.notices.last())
        s = s.onDevicesChanged(listOf(builtin, usb)) // Same list again: no duplicate notice.
        assertEquals(2, s.notices.size)
        assertEquals("selector locked", s, s.select(builtin.id))
    }

    @Test
    fun routingDifferenceWarnsOnce() {
        var s = AudioSourceSelection().onDevicesChanged(listOf(builtin, usb)).onRecordingStarted()
        assertEquals(s, s.onRouted(usb.id))
        s = s.onRouted(builtin.id).onRouted(builtin.id)
        assertEquals(listOf<AudioSourceNotice>(RoutedElsewhere(usb, builtin.id)), s.notices)
        assertEquals("not while idle", s.notices, s.onRecordingStopped().onRouted(9).notices)
    }

    @Test
    fun afterTheTakeALostSourceIsReplacedAndNoticesCanBeDismissed() {
        var s = AudioSourceSelection().onDevicesChanged(listOf(builtin, usb)).onRecordingStarted()
        s = s.onDevicesChanged(listOf(builtin)).onDevicesChanged(listOf(builtin, bt))
        s = s.onRecordingStopped()
        assertFalse(s.recording)
        assertEquals(bt, s.selected)
        assertEquals(listOf(SourceLostDuringTake(usb), NewDeviceDuringTake(bt)), s.notices)
        s = s.dismiss(SourceLostDuringTake(usb))
        assertEquals(listOf<AudioSourceNotice>(NewDeviceDuringTake(bt)), s.notices)
    }
}
