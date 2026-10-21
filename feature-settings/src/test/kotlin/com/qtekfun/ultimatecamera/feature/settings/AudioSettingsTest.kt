package com.qtekfun.ultimatecamera.feature.settings

import com.qtekfun.ultimatecamera.core.audio.AudioInputKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSettingsTest {
    @Test
    fun everyInputKindHasItsOwnLabel() {
        assertEquals(AudioInputKind.entries.size, AudioInputKind.entries.map(::kindLabel).distinct().size)
    }
}
