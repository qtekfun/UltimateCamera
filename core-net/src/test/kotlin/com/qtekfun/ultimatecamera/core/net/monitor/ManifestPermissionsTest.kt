package com.qtekfun.ultimatecamera.core.net.monitor

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression (D77): the app shipped without ACCESS_NETWORK_STATE. libwebrtc's NetworkMonitorAutoDetect (bundled
 * libwebrtc.jar, m149) calls ConnectivityManager.getActiveNetworkInfo() and registerNetworkCallback() with no
 * SecurityException handler, from native code through JNI, so the first monitor video session after pairing aborted
 * the process. core-net owns libwebrtc, so its manifest must declare what libwebrtc needs; the app merges it.
 */
class ManifestPermissionsTest {
    private fun declaredPermissions(): Set<String> {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("core-net must have a manifest: ${manifest.absolutePath}", manifest.isFile)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val nodes = doc.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItemNS(ANDROID_NS, "name").nodeValue }.toSet()
    }

    @Test
    fun declaresEveryPermissionLibwebrtcsNetworkMonitorNeeds() {
        val declared = declaredPermissions()
        for (needed in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
            assertTrue("$needed missing from core-net's manifest: $declared", needed in declared)
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
