package com.qtekfun.ultimatecamera.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputChoiceTest {
    private val bottom = AudioSource(1, AudioInputKind.BUILTIN, "Pixel 8 (bottom)", key = AudioInputChoice.key(AudioInputKind.BUILTIN, "Pixel 8", "bottom"))
    private val back = AudioSource(2, AudioInputKind.BUILTIN, "Pixel 8 (back)", key = AudioInputChoice.key(AudioInputKind.BUILTIN, "Pixel 8", "back"))
    private val buds = AudioSource(3, AudioInputKind.BLUETOOTH, "Buds", callQuality = true)
    private val usb = AudioSource(4, AudioInputKind.USB, "Rode AI-Micro")

    @Test
    fun keysAreStableAcrossReconnectionsAndTellBuiltInMicsApart() {
        assertEquals("USB:Rode AI-Micro", AudioInputChoice.key(AudioInputKind.USB, " Rode AI-Micro ", "card=1;device=4"))
        assertEquals(AudioInputChoice.key(AudioInputKind.USB, "Rode AI-Micro"), AudioInputChoice.key(AudioInputKind.USB, "Rode AI-Micro", "card=2;device=7"))
        assertEquals("BUILTIN:Pixel 8:back", back.key)
        assertTrue(bottom.key != back.key)
    }

    @Test
    fun automaticChoicePrefersUsbThenBluetoothThenBuiltIn() {
        assertEquals(usb, AudioInputChoice.resolve(listOf(bottom, back, buds, usb), storedKey = null))
        assertEquals(buds, AudioInputChoice.resolve(listOf(bottom, buds), storedKey = null))
        assertEquals(bottom, AudioInputChoice.resolve(listOf(bottom, back), storedKey = null))
        assertNull(AudioInputChoice.resolve(emptyList(), storedKey = null))
    }

    @Test
    fun aStoredPickWinsWhilePresentAndFallsBackWhenMissing() {
        val all = listOf(bottom, back, buds, usb)
        // Same device, new id after a reconnection: still matched by key.
        assertEquals(back.copy(id = 42), AudioInputChoice.resolve(all - back + back.copy(id = 42), back.key))
        assertFalse(AudioInputChoice.isMissing(all, back.key))
        assertEquals(buds, AudioInputChoice.resolve(listOf(bottom, buds), usb.key))
        assertTrue(AudioInputChoice.isMissing(listOf(bottom, buds), usb.key))
        assertFalse(AudioInputChoice.isMissing(listOf(bottom), null))
    }
}
