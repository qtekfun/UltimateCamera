package com.qtekfun.ultimatecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCodeTest {
    @Test
    fun versionNameFollowsThePhaseScheme() {
        assertTrue(BuildConfig.VERSION_NAME, Regex("""\d+\.\d+\.\d+(-fase\d+)?(-debug)?""").matches(BuildConfig.VERSION_NAME))
    }

    @Test
    fun versionCodeIsDerivedFromTheVersionName() {
        val (major, minor, patch) = BuildConfig.VERSION_NAME.substringBefore('-').split('.').map { it.toInt() }
        assertEquals(major * 1_000_000 + minor * 1_000 + patch, BuildConfig.VERSION_CODE)
    }
}
