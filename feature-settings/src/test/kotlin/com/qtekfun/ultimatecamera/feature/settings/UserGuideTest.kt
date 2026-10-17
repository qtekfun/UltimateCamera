package com.qtekfun.ultimatecamera.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** D67: Settings › Help opens the guide in the app's language from the repository. */
class UserGuideTest {
    @Test
    fun `spanish locales open the spanish guide`() {
        assertEquals("https://github.com/qtekfun/UltimateCamera/blob/main/docs/user-guide/es.md", UserGuide.url("es-ES"))
        assertEquals("https://github.com/qtekfun/UltimateCamera/blob/main/docs/user-guide/es.md", UserGuide.url("es-419"))
        assertEquals("https://github.com/qtekfun/UltimateCamera/blob/main/docs/user-guide/es.md", UserGuide.url("ES"))
    }

    @Test
    fun `any other locale opens the english guide`() {
        assertEquals("https://github.com/qtekfun/UltimateCamera/blob/main/docs/user-guide/en.md", UserGuide.url("en-GB"))
        assertEquals("https://github.com/qtekfun/UltimateCamera/blob/main/docs/user-guide/en.md", UserGuide.url("fr"))
        assertEquals("https://github.com/qtekfun/UltimateCamera/blob/main/docs/user-guide/en.md", UserGuide.url(""))
    }
}
