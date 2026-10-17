package com.qtekfun.ultimatecamera.feature.settings

import com.qtekfun.ultimatecamera.core.gpu.style.StyleParams
import com.qtekfun.ultimatecamera.core.gpu.style.StylePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StyleSettingsTest {
    @Test
    fun standardIsStoredAsNoTokenAndOthersAsTheirDefaults() {
        assertNull(presetToken(StylePreset.STANDARD))
        for (preset in StylePreset.entries.filter { it != StylePreset.STANDARD }) {
            assertEquals(StyleParams.of(preset), StyleParams.parse(presetToken(preset)))
        }
    }
}
