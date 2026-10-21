package com.qtekfun.ultimatecamera.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraReportSettingsTest {
    @Test
    fun aFailureWhileReadingIsShownAsOneLine() {
        assertEquals("camera 0", reportOrError { "camera 0" })
        assertEquals("IllegalStateException: hal", reportOrError { throw IllegalStateException("hal") })
    }
}
