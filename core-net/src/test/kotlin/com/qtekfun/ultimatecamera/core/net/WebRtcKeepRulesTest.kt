package com.qtekfun.ultimatecamera.core.net

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRtcKeepRulesTest {
    private val rules = File("consumer-rules.pro").readLines().map { it.trim() }

    @Test
    fun everyPackageLibwebrtcLooksUpByNameIsKept() {
        // Regression (D95): libwebrtc m149 looks up org.jni_zero.JniZero from JNI_OnLoad; R8 renamed it and every
        // release build aborted on the first WebRTC use (the monitor link). Debug builds are not minified and worked.
        for (pkg in listOf("org.webrtc", "org.jni_zero")) {
            assertTrue("keep rule for $pkg", "-keep class $pkg.** { *; }" in rules)
        }
    }
}
