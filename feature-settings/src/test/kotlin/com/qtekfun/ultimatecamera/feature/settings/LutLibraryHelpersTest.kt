package com.qtekfun.ultimatecamera.feature.settings

import com.qtekfun.ultimatecamera.core.luts.LutEntry
import com.qtekfun.ultimatecamera.core.luts.LutFormatName
import com.qtekfun.ultimatecamera.core.luts.LutLibraryException
import com.qtekfun.ultimatecamera.core.luts.LutOrigin
import com.qtekfun.ultimatecamera.core.luts.catalogue.DownloadException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class LutLibraryHelpersTest {
    @Test
    fun childPathsJoinWithSlashes() {
        assertEquals("OPPO", child("", "OPPO"))
        assertEquals("Looks/Film", child("Looks", "Film"))
    }

    @Test
    fun breadcrumbGoesFromTheRootDown() {
        assertEquals(listOf(""), breadcrumb(""))
        assertEquals(listOf("", "A", "A/B", "A/B/C"), breadcrumb("A/B/C"))
    }

    @Test
    fun onlyCatalogueDownloadsCountAsDownloaded() {
        fun entry(id: String, origin: LutOrigin) = LutEntry(id, id, "", LutFormatName.CUBE, 2, "$id.cube", origin, "0", 1, 0)
        val luts = listOf(
            entry("a", LutOrigin.catalogue("oppo-o-log2-to-rec709", "https://x/y.zip")),
            entry("b", LutOrigin.imported("mine.cube")),
            entry("c", LutOrigin.generated)
        )
        assertEquals(setOf("oppo-o-log2-to-rec709"), downloadedCatalogueIds(luts))
    }

    @Test
    fun failuresMapToTheirMessages() {
        assertEquals(LibraryProblem.INVALID_LUT, LibraryProblem.of(LutLibraryException(LutLibraryException.Reason.INVALID_LUT, "x")))
        assertEquals(LibraryProblem.STORAGE, LibraryProblem.of(LutLibraryException(LutLibraryException.Reason.INDEX_TOO_NEW, "x")))
        assertEquals(LibraryProblem.CHECKSUM, LibraryProblem.of(DownloadException(DownloadException.Reason.CHECKSUM_MISMATCH, "x")))
        assertEquals(LibraryProblem.CHECKSUM, LibraryProblem.of(DownloadException(DownloadException.Reason.ENTRY_MISSING, "x")))
        assertEquals(LibraryProblem.NETWORK, LibraryProblem.of(DownloadException(DownloadException.Reason.NOT_HTTPS, "x")))
        assertEquals(LibraryProblem.TOO_LARGE, LibraryProblem.of(DownloadException(DownloadException.Reason.TOO_LARGE, "x")))
        assertEquals(LibraryProblem.STORAGE, LibraryProblem.of(IOException("disk")))
    }
}
